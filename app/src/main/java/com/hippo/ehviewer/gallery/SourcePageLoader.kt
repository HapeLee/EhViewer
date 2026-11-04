package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.ui.reader.PageSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 把现有的 [PageSource]（在线 / 下载 / 压缩包三合一）接到 EHz 的 [PageLoader] 上。
 *
 * 这样阅读器的视图与菜单可以按 EHz 原样复刻，取字节这层仍用本仓已经跑通的那套：
 * SpiderQueen 下载、压缩包会话解压、会话缓存目录的生命周期都留在 [PageSource] 里。
 */
class SourcePageLoader(
    scope: CoroutineScope,
    private val pageSource: PageSource,
    startPage: Int,
    gid: Long = -1L,
    title: String = pageSource.title,
) : PageLoader(scope, startPage, pageSource.count, gid) {

    private val hostScope = scope

    private val originalDir: File by lazy {
        File(application.cacheDir, "reader-original").apply { mkdirs() }
    }

    init {
        this.title = title
        // 下载进度只对在线来源有意义；不确定进度（percent == null）按 0 处理，
        // 界面上就是一个「在动但没百分比」的进度圈
        scope.launch {
            pageSource.progress.collect { progress ->
                if (progress.index !in pages.indices) return@collect
                notifyPagePercent(progress.index, (progress.percent ?: 0).coerceIn(0, 100) / 100f)
            }
        }
    }

    override suspend fun openSource(index: Int, original: Boolean): ImageSource? {
        val file = if (original) {
            pageSource.original(index, originalDir)
        } else {
            pageSource.page(index)
        }
        return file?.let(::PathSource)
    }

    override fun prefetchPages(pages: List<Int>) {
        pages.forEach { index ->
            // 只落盘：page() 自己会复用会话缓存，重复请求是便宜的
            hostScope.launch { runCatching { pageSource.page(index) } }
        }
    }

    /** 页面动作（分享 / 保存 / 另存）要的是落盘文件，不是解码后的位图 */
    suspend fun file(index: Int, original: Boolean = false): File? = runCatching {
        if (original) pageSource.original(index, originalDir) else pageSource.page(index)
    }.getOrNull()

    override fun imageFilename(index: Int): String = pageSource.name(index)

    override fun onClose() {
        pageSource.putStartPage(startPage)
        runCatching { originalDir.deleteRecursively() }
    }
}
