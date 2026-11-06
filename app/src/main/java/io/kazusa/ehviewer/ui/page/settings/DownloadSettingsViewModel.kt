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
import io.kazusa.ehviewer.AppConfig
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhEngine.fillGalleryListByApi
import io.kazusa.ehviewer.client.EhUrl
import io.kazusa.ehviewer.client.data.BaseGalleryInfo
import io.kazusa.ehviewer.client.parser.GalleryDetailUrlParser
import io.kazusa.ehviewer.download.DownloadManager
import io.kazusa.ehviewer.spider.SpiderDen.Companion.getGalleryDownloadDir
import io.kazusa.ehviewer.spider.SpiderInfo
import io.kazusa.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import io.kazusa.ehviewer.spider.readFromUniFile
import io.kazusa.ehviewer.spider.saveToUniFile
import io.kazusa.ehviewer.support.unifile.UniFile
import io.kazusa.ehviewer.support.unifile.openInputStream
import io.kazusa.ehviewer.support.util.runSuspendCatching
import io.kazusa.ehviewer.ui.keepNoMediaFileStatus
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * 恢复下载项的进度快照。
 *
 * [scanning] 为真时 [done] / [total] 是「已读目录数 / 目录总数」，否则是「已落库项数 / 待恢复项数」；
 * `total == 0` 表示总数还没数出来（刚点下去、目录还没列完），此时进度条走不确定态。
 */
data class RestoreProgress(val done: Int, val total: Int, val scanning: Boolean)

/**
 * 下载页里三个「有 IO」的项：下载目录、恢复下载、清理冗余。
 *
 * 老实现是两个 `TaskPreference` 子类（各带一个转圈对话框）+ `DownloadFragment`
 * 里的 SAF 回调。这里把「干什么」搬进 ViewModel，「怎么选文件」留给场景（Compose 的
 * `rememberLauncherForActivityResult`），进度用 [restoreProgress] 表达
 * （页面据此置灰两个动作并弹一个不可点外部关闭的进度框）。
 *
 * 文件扫描一律 `Dispatchers.IO`：老代码在 `lifecycleScope.launch` 里直接 listFiles，
 * 大目录会卡主线程。
 */
class DownloadSettingsViewModel(private val app: Application) : ViewModel() {
    var downloadLocationUri by mutableStateOf(Settings.downloadLocation?.uri?.toString())
        private set

    var running by mutableStateOf(false)
        private set

    /** 恢复下载项的实时进度；`null` 表示没在跑 */
    var restoreProgress by mutableStateOf<RestoreProgress?>(null)
        private set

    /** 一次性提示：场景消费后调 [consumeTip] */
    var tip by mutableStateOf<String?>(null)
        private set

    private var restoreJob: Job? = null

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
        restoreProgress = RestoreProgress(done = 0, total = 0, scanning = true)
        restoreJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { doRestore { restoreProgress = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 兜底：扫描/写库抛出来的意外不该把进程带走，给一句「恢复失败」就够
                Log.e(TAG, "Restore downloads failed", e)
                app.getString(R.string.settings_download_restore_failed)
            } finally {
                restoreProgress = null
                running = false
                restoreJob = null
            }
            tip = result
        }
    }

    /**
     * 取消正在跑的恢复。
     *
     * 已经写进下载列表的项保留——恢复是幂等的，再点一次只会补上还没落库的部分。
     */
    fun cancelRestore() {
        restoreJob?.cancel()
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

    private suspend fun doRestore(report: (RestoreProgress) -> Unit): String {
        val dir = Settings.downloadLocation ?: return app.getString(R.string.settings_download_restore_failed)
        val children = dir.listFiles() ?: return app.getString(R.string.settings_download_restore_failed)

        // 阶段一：只读目录，不碰 DownloadManager 的内存态（那不是线程安全的）
        val scanned = scanDirs(children, report)

        // 阶段二：目录和下载列表都在的，只把目录名对齐，避免重复下载
        var alignedCount = 0
        val pending = ArrayList<ScannedItem>(scanned.size)
        for (item in scanned) {
            if (DownloadManager.containDownloadInfo(item.gid)) {
                val dbDirname = DownloadManager.getDownloadDirname(item.gid)
                if (dbDirname == null || item.dirname != dbDirname) {
                    DownloadManager.putDownloadDirname(item.gid, item.dirname)
                    alignedCount++
                }
                continue
            }
            pending += item
        }
        if (pending.isEmpty()) return restoreCountMessage(alignedCount)

        // 阶段三：分批补元数据再落库。一批抓不到不算整次失败——目录本来就在本地，
        // 1k 本要打四十多个请求，不该因为其中一个超时就让用户白等一场
        val items = pending.mapTo(ArrayList(pending.size)) { it.toRestoreItem() }
        var done = 0
        report(RestoreProgress(done = 0, total = items.size, scanning = false))
        for (chunk in items.chunked(API_BATCH_SIZE)) {
            try {
                fillGalleryListByApi(chunk, EhUrl.referer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Can't fill gallery info for ${chunk.size} items", e)
            }
            for (item in chunk) {
                // 元数据没拿到就没有标题：退回目录名，至少让它回到列表里
                if (item.title == null) item.title = titleOf(item.dirname)
                writeRestoredItem(item)
                done++
            }
            report(RestoreProgress(done = done, total = items.size, scanning = false))
        }
        return restoreCountMessage(done + alignedCount)
    }

    /**
     * 并发读下载目录里每个子目录的 `.ehviewer` / `ComicInfo.xml`。
     *
     * SAF 的每一次 `findFile` / `open` 都是一次 binder 往返，串行扫 1k 个目录要好几分钟；
     * 并发度取 [SCAN_CONCURRENCY]，再高会把 DocumentsProvider 的线程池排满、反而更慢。
     */
    private suspend fun scanDirs(
        children: Array<UniFile>,
        report: (RestoreProgress) -> Unit,
    ): List<ScannedItem> {
        val total = children.size
        report(RestoreProgress(done = 0, total = total, scanning = true))
        if (total == 0) return emptyList()
        val counter = AtomicInteger()
        val results = Collections.synchronizedList(ArrayList<ScannedItem>(total))
        coroutineScope {
            val gate = Semaphore(SCAN_CONCURRENCY)
            children.map { child ->
                async {
                    gate.withPermit {
                        scanItem(child)?.let(results::add)
                        val done = counter.incrementAndGet()
                        // 进度回调按步长合并，免得 1k 个目录把界面刷爆
                        if (done % PROGRESS_STEP == 0 || done == total) {
                            report(RestoreProgress(done = done, total = total, scanning = true))
                        }
                    }
                }
            }.awaitAll()
        }
        return results.toList()
    }

    private fun scanItem(dir: UniFile): ScannedItem? {
        if (!dir.isDirectory) return null
        return runCatching {
            val dirname = dir.name ?: return null
            dir.findFile(SPIDER_INFO_FILENAME)?.let { file ->
                val info = readFromUniFile(file) ?: return null
                val token = info.token ?: return null
                return ScannedItem(dirname, info.gid, token, fromComicInfo = false)
            }
            val parsed = dir.findFile(COMIC_INFO_FILE)?.let { file ->
                file.openInputStream().source().buffer().use { GalleryDetailUrlParser.parse(it.readUtf8()) }
            } ?: return null
            ScannedItem(dirname, parsed.gid, parsed.token, fromComicInfo = true)
        }.onFailure {
            Log.e(TAG, "Can't scan ${dir.uri}", it)
        }.getOrNull()
    }

    private suspend fun writeRestoredItem(item: RestoreItem) {
        DownloadManager.addDownload(item, null)
        DownloadManager.putDownloadDirname(item.gid, item.dirname)
        // 靠 ComicInfo.xml 认出来的目录缺 .ehviewer 文件，补一份。
        // 没拿到元数据（页数未知）时不补：写一份 0 页的 SpiderInfo 会让画廊看起来是空的
        if (item.fromComicInfo && item.pages > 0) {
            getGalleryDownloadDir(item.gid)?.run {
                createFile(SPIDER_INFO_FILENAME)?.also {
                    SpiderInfo(item.gid, item.token, item.pages).saveToUniFile(it)
                }
            }
        }
    }

    private fun restoreCountMessage(count: Int): String = if (count == 0) {
        app.getString(R.string.settings_download_restore_not_found)
    } else {
        app.getString(R.string.settings_download_restore_successfully, count)
    }

    /** 目录名形如 `1234567-[Title]`，取不到元数据时拿后半截当标题 */
    private fun titleOf(dirname: String): String = dirname.substringAfter('-', dirname)

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

    /** 阶段一的产物：一个子目录认出来的 gid / token，此时还没碰过 DownloadManager */
    private class ScannedItem(
        val dirname: String,
        val gid: Long,
        val token: String,
        val fromComicInfo: Boolean,
    ) {
        fun toRestoreItem(): RestoreItem = RestoreItem(dirname, gid, token, fromComicInfo)
    }

    @SuppressLint("ParcelCreator")
    private class RestoreItem(
        val dirname: String,
        gid: Long,
        token: String,
        /** 靠 ComicInfo.xml 认出来的目录缺 `.ehviewer`，落库后要补一份 */
        val fromComicInfo: Boolean,
    ) : BaseGalleryInfo(gid, token)

    private companion object {
        private val TAG = DownloadSettingsViewModel::class.java.simpleName
        private const val COMIC_INFO_FILE = "ComicInfo.xml"

        /** 一次 gdata 请求最多 25 个 gid，与 `EhEngine.fillGalleryListByApi` 内部的分批一致 */
        private const val API_BATCH_SIZE = 25

        /** 并发扫目录的路数 */
        private const val SCAN_CONCURRENCY = 8

        /** 进度回调的最小步长（按目录数） */
        private const val PROGRESS_STEP = 16
    }
}
