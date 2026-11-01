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
package com.hippo.ehviewer.ui.page.settings

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine.fillGalleryListByApi
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.SpiderDen.Companion.getGalleryDownloadDir
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.readFromUniFile
import com.hippo.ehviewer.spider.saveToUniFile
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.unifile.UniFile
import com.hippo.unifile.openInputStream
import com.hippo.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * 下载页里三个「有 IO」的项：下载目录、恢复下载、清理冗余。
 *
 * 老实现是两个 `TaskPreference` 子类（各带一个转圈对话框）+ `DownloadFragment`
 * 里的 SAF 回调。这里把「干什么」搬进 ViewModel，「怎么选文件」留给场景（Compose 的
 * `rememberLauncherForActivityResult`），进度用 [running] 表达（页面据此置灰两个动作）。
 *
 * 文件扫描一律 `Dispatchers.IO`：老代码在 `lifecycleScope.launch` 里直接 listFiles，
 * 大目录会卡主线程。
 */
class DownloadSettingsViewModel(private val app: Application) : ViewModel() {
    var downloadLocationUri by mutableStateOf(Settings.downloadLocation?.uri?.toString())
        private set

    var running by mutableStateOf(false)
        private set

    /** 一次性提示：场景消费后调 [consumeTip] */
    var tip by mutableStateOf<String?>(null)
        private set

    fun consumeTip(): String? = tip.also { tip = null }

    fun setDownloadLocation(uri: Uri) {
        viewModelScope.launch {
            runSuspendCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val uniFile = UniFile.fromTreeUri(app, uri)
            if (uniFile == null) {
                tip = app.getString(R.string.settings_download_cant_get_download_location)
                return@launch
            }
            applyDownloadLocation(uniFile)
        }
    }

    fun resetDownloadLocation() {
        viewModelScope.launch {
            val uniFile = UniFile.fromFile(AppConfig.getDefaultDownloadDir())
            if (uniFile == null) {
                tip = app.getString(R.string.settings_download_cant_get_download_location)
                return@launch
            }
            applyDownloadLocation(uniFile)
        }
    }

    private suspend fun applyDownloadLocation(uniFile: UniFile) {
        Settings.putDownloadLocation(uniFile)
        keepNoMediaFileStatus()
        downloadLocationUri = uniFile.uri.toString()
    }

    /** 扫描下载目录，把「有目录但数据库里没有」的画廊补回下载列表 */
    fun restoreDownloads() {
        if (running) return
        running = true
        viewModelScope.launch {
            tip = try {
                withContext(Dispatchers.IO) { doRestore() }
            } finally {
                running = false
            }
        }
    }

    /** 删除「数据库里已经没有」的下载目录 */
    fun cleanRedundancy() {
        if (running) return
        running = true
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                runSuspendCatching {
                    Settings.downloadLocation?.listFiles()?.sumOf { clearRedundantFile(it).compareTo(false) }
                        ?: 0
                }.getOrDefault(0)
            }
            running = false
            tip = if (count == 0) {
                app.getString(R.string.settings_download_clean_redundancy_no_redundancy)
            } else {
                app.getString(R.string.settings_download_clean_redundancy_done, count)
            }
        }
    }

    private suspend fun doRestore(): String {
        val dir = Settings.downloadLocation ?: return app.getString(R.string.settings_download_restore_failed)
        val files = dir.listFiles() ?: return app.getString(R.string.settings_download_restore_failed)
        var restoredDirCount = 0
        val nonSpiderInfoGids = mutableSetOf<Long>()

        suspend fun getRestoreItem(child: UniFile): RestoreItem? {
            if (!child.isDirectory) return null
            return runCatching {
                val result = child.findFile(SPIDER_INFO_FILENAME)?.let {
                    readFromUniFile(it)?.run { GalleryDetailUrlParser.Result(gid, token!!) }
                } ?: child.findFile(COMIC_INFO_FILE)?.let { file ->
                    file.openInputStream().source().buffer().use { GalleryDetailUrlParser.parse(it.readUtf8()) }
                        ?.also { nonSpiderInfoGids.add(it.gid) }
                } ?: return null
                val gid = result.gid
                val dirname = child.name!!
                if (DownloadManager.containDownloadInfo(gid)) {
                    // 目录存在、数据库里也有：只把目录名对齐，避免重复下载
                    val dbDirname = DownloadManager.getDownloadDirname(gid)
                    if (dbDirname == null || dirname != dbDirname) {
                        DownloadManager.putDownloadDirname(gid, dirname)
                        restoredDirCount++
                    }
                    return null
                }
                RestoreItem(dirname, gid, result.token)
            }.onFailure {
                Log.e(TAG, "Error", it)
            }.getOrNull()
        }

        val items = runSuspendCatching {
            files.mapNotNull { getRestoreItem(it) }.also { fillGalleryListByApi(it, EhUrl.referer) }
        }.onFailure {
            Log.e(TAG, "Error", it)
        }.getOrNull() ?: return app.getString(R.string.settings_download_restore_failed)

        if (items.isEmpty()) return restoreCountMessage(restoredDirCount)

        var count = 0
        items.forEach { item ->
            if (item.title != null) {
                val gid = item.gid
                DownloadManager.addDownload(item, null)
                DownloadManager.putDownloadDirname(gid, item.dirname)
                // 靠 ComicInfo.xml 认出来的目录缺 .ehviewer 文件，补一份
                if (gid in nonSpiderInfoGids) {
                    getGalleryDownloadDir(gid)?.run {
                        createFile(SPIDER_INFO_FILENAME)?.also {
                            SpiderInfo(gid, item.token, item.pages).saveToUniFile(it)
                        }
                    }
                }
                count++
            }
        }
        return restoreCountMessage(count + restoredDirCount)
    }

    private fun restoreCountMessage(count: Int): String = if (count == 0) {
        app.getString(R.string.settings_download_restore_not_found)
    } else {
        app.getString(R.string.settings_download_restore_successfully, count)
    }

    private fun clearRedundantFile(file: UniFile): Boolean {
        var name = file.name ?: return false
        val index = name.indexOf('-')
        if (index >= 0) {
            name = name.substring(0, index)
        }
        val gid = name.toLongOrNull() ?: return false
        if (DownloadManager.containDownloadInfo(gid)) {
            return false
        }
        file.delete()
        return true
    }

    @SuppressLint("ParcelCreator")
    private class RestoreItem(val dirname: String, gid: Long, token: String) : BaseGalleryInfo(gid, token)

    private companion object {
        private val TAG = DownloadSettingsViewModel::class.java.simpleName
        private const val COMIC_INFO_FILE = "ComicInfo.xml"
    }
}
