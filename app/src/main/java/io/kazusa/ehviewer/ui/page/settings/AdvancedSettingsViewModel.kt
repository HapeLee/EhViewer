/*
 * Copyright 2026 EhViewer contributors
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package io.kazusa.ehviewer.ui.page.settings

import android.app.Application
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kazusa.ehviewer.AppConfig
import io.kazusa.ehviewer.EhApplication
import io.kazusa.ehviewer.EhDB
import io.kazusa.ehviewer.EhProxySelector
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhEngine
import io.kazusa.ehviewer.client.data.FavListUrlBuilder
import io.kazusa.ehviewer.client.data.GalleryInfo
import io.kazusa.ehviewer.support.network.InetValidator
import io.kazusa.ehviewer.support.okhttp.CHROME_USER_AGENT
import io.kazusa.ehviewer.support.util.LogCat
import io.kazusa.ehviewer.support.util.ReadableTime
import io.kazusa.ehviewer.support.yorozuya.IOUtils
import io.kazusa.ehviewer.support.yorozuya.MathUtils
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 高级页里所有「离开 UI 才能做」的事：代理与 UA 的状态、备份收藏、导出/导入数据库、
 * 打包日志。
 *
 * 老实现是 `AdvancedFragment`（408 行，三个 SAF 回调 + 一个分页的 `EhRequest` 回调）
 * 加 `ProxyPreference` / `UserAgentPreference` 两个 `DialogPreference`。这里拆成
 * 「状态在 VM、选文件在场景、对话框在 Compose」三块，行为保持一致：
 * - 代理摘要的降级规则（HTTP/SOCKS 但 ip 或端口不合法时显示成「跟随系统」）；
 * - UA 留空回落成 `CHROME_USER_AGENT`；
 * - 备份收藏仍然是分页抓取 + `downloadDelay` 间隔，每页更新一次提示。
 */
class AdvancedSettingsViewModel(private val app: Application) : ViewModel() {
    var proxySummary by mutableStateOf(
        formatProxySummary(Settings.proxyType, Settings.proxyIp, Settings.proxyPort),
    )
        private set

    var userAgent by mutableStateOf(Settings.userAgent)
        private set

    /** 长任务（备份 / 导出 / 导入 / 打包日志）进行中：页面据此置灰这几个动作 */
    var busy by mutableStateOf(false)
        private set

    var tip by mutableStateOf<String?>(null)
        private set

    fun consumeTip(): String? = tip.also { tip = null }

    val proxyTypes: Array<String>
        get() = app.resources.getStringArray(R.array.proxy_types)

    val currentProxyType: Int
        get() = Settings.proxyType

    val currentProxyIp: String?
        get() = Settings.proxyIp

    val currentProxyPort: Int
        get() = Settings.proxyPort

    fun proxyTypeLabel(type: Int): String = proxyTypes[MathUtils.clamp(type, 0, proxyTypes.size - 1)]

    fun isValidPort(port: Int): Boolean = InetValidator.isValidInetPort(port)

    fun saveProxy(type: Int, ip: String, port: Int) {
        Settings.putProxyType(type)
        Settings.putProxyIp(ip)
        Settings.putProxyPort(port)
        proxySummary = formatProxySummary(type, ip, port)
        EhApplication.ehProxySelector.updateProxy()
    }

    fun saveUserAgent(value: String?) {
        val ua = value?.trim()?.ifBlank { null } ?: CHROME_USER_AGENT
        Settings.putUserAgent(ua)
        userAgent = ua
    }

    fun exportData(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) { runCatching { EhDB.exportDB(app, uri) }.getOrDefault(false) }
            busy = false
            tip = if (success) {
                app.getString(R.string.settings_advanced_export_data_to, uri.toString())
            } else {
                app.getString(R.string.settings_advanced_export_data_failed)
            }
        }
    }

    fun importData(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { EhDB.importDB(app, uri) } }
            busy = false
            tip = result.getOrNull()
                ?: result.exceptionOrNull()?.localizedMessage
                ?: app.getString(R.string.settings_advanced_import_data_successfully)
        }
    }

    /** 打包临时目录里的解析错误页 + 崩溃日志 + 当前 logcat，写到用户选的文件 */
    fun dumpLogcat(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val finished = withContext(Dispatchers.IO) { runCatching { saveLogs(uri) }.getOrDefault(false) }
            busy = false
            tip = if (finished) {
                app.getString(R.string.settings_advanced_dump_logcat_to, uri.toString())
            } else {
                app.getString(R.string.settings_advanced_dump_logcat_failed)
            }
        }
    }

    private fun saveLogs(uri: Uri): Boolean {
        val zipFile = File(AppConfig.getExternalTempDir(), "logs.zip")
        if (zipFile.exists()) zipFile.delete()
        val files = ArrayList<File>()
        AppConfig.getExternalParseErrorDir()?.listFiles()?.let { files.addAll(it) }
        AppConfig.getExternalCrashDir()?.listFiles()?.let { files.addAll(it) }
        var origin: BufferedInputStream? = null
        var out: ZipOutputStream? = null
        try {
            out = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))
            val bytes = ByteArray(1024 * 64)
            for (file in files) {
                if (!file.isFile) continue
                runCatching {
                    origin = BufferedInputStream(FileInputStream(file), bytes.size)
                    out.putNextEntry(ZipEntry(file.name))
                    var count: Int
                    while (origin!!.read(bytes, 0, bytes.size).also { count = it } != -1) {
                        out.write(bytes, 0, count)
                    }
                    origin!!.close()
                    origin = null
                }.onFailure { Log.e(TAG, "Error", it) }
            }
            out.putNextEntry(ZipEntry("logcat-" + ReadableTime.getFilenamableTime() + ".txt"))
            LogCat.save(out!!)
            out.closeEntry()
            out.close()
            IOUtils.copy(FileInputStream(zipFile), app.contentResolver.openOutputStream(uri))
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            return runCatching {
                LogCat.save(app.contentResolver.openOutputStream(uri)!!)
            }.getOrDefault(false)
        } finally {
            runCatching { origin?.close() }
            runCatching { out?.close() }
        }
    }

    /** 分页抓收藏并写本地库（`EhDB.putLocalFavorites`），每页一次提示 */
    fun backupFavorites() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val builder = FavListUrlBuilder()
                var url = builder.build()
                var page = 1
                var total = 0
                while (true) {
                    val result = EhEngine.getFavorites(url)
                    val list: List<GalleryInfo> = result.galleryInfoList
                    if (list.isEmpty()) {
                        tip = app.getString(R.string.settings_advanced_backup_favorite_nothing)
                        return@launch
                    }
                    if (total == 0) {
                        var totalFav = 0
                        for (i in 0..9) totalFav += result.countArray[i]
                        total = ceil(totalFav.toDouble() / list.size).toInt()
                    }
                    tip = app.getString(R.string.settings_advanced_backup_favorite_start, "($page/$total)")
                    EhDB.putLocalFavorites(list)
                    val next = result.next ?: break
                    delay(Settings.downloadDelay.toLong())
                    page++
                    builder.setIndex(next, true)
                    url = builder.build()
                }
                tip = app.getString(R.string.settings_advanced_backup_favorite_success)
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                tip = app.getString(R.string.settings_advanced_backup_favorite_failed)
            } finally {
                busy = false
            }
        }
    }

    private fun formatProxySummary(type: Int, ip: String?, port: Int): String {
        val effectiveType = if ((type == EhProxySelector.TYPE_HTTP || type == EhProxySelector.TYPE_SOCKS) &&
            (TextUtils.isEmpty(ip) || !InetValidator.isValidInetPort(port))
        ) {
            EhProxySelector.TYPE_SYSTEM
        } else {
            type
        }
        return if (effectiveType == EhProxySelector.TYPE_HTTP || effectiveType == EhProxySelector.TYPE_SOCKS) {
            app.getString(
                R.string.settings_advanced_proxy_summary_1,
                proxyTypeLabel(effectiveType),
                ip,
                port,
            )
        } else {
            app.getString(R.string.settings_advanced_proxy_summary_2, proxyTypeLabel(effectiveType))
        }
    }

    private companion object {
        private val TAG = AdvancedSettingsViewModel::class.java.simpleName
    }
}
