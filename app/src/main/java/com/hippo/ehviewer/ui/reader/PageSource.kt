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
package com.hippo.ehviewer.ui.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.archive.ArchiveEntry
import com.hippo.ehviewer.archive.ArchiveOpener
import com.hippo.ehviewer.archive.ArchivePasswordRequiredException
import com.hippo.ehviewer.archive.ArchiveSession
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderDen.Companion.SUPPORT_IMAGE_EXTENSIONS
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.obtainSpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.releaseSpiderQueen
import com.hippo.ehviewer.spider.readFromUniFile
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ReaderSource
import com.hippo.image.Image
import com.hippo.unifile.UniFile
import com.hippo.unifile.openInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 页文件名：与 `SpiderDen.perFilename` 一样按 1 基序号排，翻页目录里好认 */
private const val PAGE_NAME_FORMAT = "%08d"

/** 分享 / 另存 / 原图用的文件名，与旧 `EhGalleryProvider.getImageFilename` 同款 */
private const val IMAGE_NAME_FORMAT = "%d-%s-%08d"

/**
 * 单页等待上限。
 *
 * 正常下载由 SpiderQueen 自己的超时兜底；这一层只是防止「画廊正在被 DownloadService
 * 下载、读模式下 `preloadPages` 不生效」时无限等待——那种情况旧实现是白屏，
 * 这里是超时后报错，至少还能重试。
 */
private const val PAGE_TIMEOUT_MS = 120_000L

/**
 * 阅读器的页数据来源。
 *
 * 在线画廊、已下载画廊、压缩包画廊最终都收敛成这一层，
 * Reader 只认 [count] / [name] / [page]，不关心数据从哪来。
 */
interface PageSource : Closeable {
    val count: Int

    /** 上次读到的位置；入口没指定页码时的起始页 */
    val startPage: Int
        get() = 0

    /**
     * 单页下载进度。
     *
     * [PageProgress.percent] 为 null 表示「不确定」：服务端没给 `Content-Length`
     * 就没有分母，UI 退化成转圈，而不是假装有百分比。
     */
    val progress: Flow<PageProgress>
        get() = emptyFlow()

    fun name(index: Int): String

    /**
     * 准备页源：取页数、建索引。返回 false 表示不可用（拿不到元数据）。
     *
     * 在线画廊的页数要等 `SpiderInfo` 就绪才知道，而 [count] 是同步的，
     * 所以「知道有多少页」单独一步，由 `ReaderRoute` 在渲染分页器之前跑完。
     */
    suspend fun prepare(): Boolean = true

    /** 取第 [index] 页落成本地文件（可在同一会话内复用缓存） */
    suspend fun page(index: Int): File

    /**
     * 回写「读到第几页」。
     *
     * 只有能跨会话记住位置的页源需要实现（在线画廊落在 `.ehviewer` 的
     * `SpiderInfo.startPage`，与旧 `GalleryActivity.onUpdateCurrentIndex` 同款）。
     * 真正的持久化（Room）在 R4。
     */
    fun putStartPage(page: Int) {
    }

    /**
     * 丢掉第 [index] 页的下载结果与本地副本，下次取会重新下一遍（页面菜单的「刷新」）。
     *
     * 已下载画廊与压缩包没有「重下」这回事，保持空实现。
     */
    fun invalidate(index: Int) {
    }

    /**
     * 取服务端原图并落到 [dir]；本地来源没有「原图」概念时返回 null。
     *
     * 与 [page] 不同：这条会额外请求一次页面拿 `originImageUrl`，可能几十 MB。
     */
    suspend fun original(index: Int, dir: File): File? = null
}

/** [PageSource.progress] 的载荷：[percent] 为 null 表示不确定进度 */
data class PageProgress(val index: Int, val percent: Int?)

/**
 * 压缩包页源：把 [ArchiveSession] 的条目按需解到缓存目录。
 *
 * 落文件而不是直接喂流：Coil 对 File 的缓存与 telephoto 的区域解码（需要可随机访问的源）
 * 都最稳。解压串行化——7z 固实包与 TAR 都不允许并发读不同条目。
 *
 * 开包这件事（含密码）在 [openArchivePageSource] 里，这一层只认已经打开的 session，
 * 因此可以直接拿假 session 单测。
 */
class ArchivePageSource(
    private val session: ArchiveSession,
    private val cacheDir: File,
    /** 页 → 压缩包条目下标；留空表示「全部非目录条目，按包内原顺序」 */
    private val pageToEntry: List<Int> = emptyList(),
    private val onClose: () -> Unit = {},
) : PageSource {
    private val mutex = Mutex()
    private val indices = pageToEntry.ifEmpty {
        session.entries.indices.filterNot { session.entries[it].isDirectory }
    }

    override val count: Int
        get() = indices.size

    override fun name(index: Int): String = session.entries[indices[index]].name

    override suspend fun page(index: Int): File = withContext(Dispatchers.IO) {
        val name = session.entries[indices[index]].name.substringAfterLast('/')
        val target = File(cacheDir, "%05d-%s".format(Locale.US, index, name))
        if (target.isFile && target.length() > 0L) return@withContext target
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${target.name}.part")
        mutex.withLock {
            session.openEntry(indices[index]).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot materialize page $index")
        }
        target
    }

    override fun close() {
        session.close()
        onClose()
        cacheDir.deleteRecursively()
    }
}

/**
 * 打开压缩包并造页源；打不开返回 null。
 *
 * 与旧 `ArchiveGalleryProvider` 的三点一致：只收图片条目、按**自然序**排
 * （`2.jpg` 排在 `10.jpg` 前面）、先试已记住的密码再问用户。
 *
 * 密码走「回调 + flow」而不是把对话框塞进页源：[onPasswordRequested] 让 UI 弹框，
 * 结果送进 [passwdFlow]（null 表示用户放弃），页源本身不碰 Android UI。
 */
suspend fun openArchivePageSource(
    context: Context,
    uri: Uri,
    passwdFlow: Flow<String?>,
    onPasswordRequested: suspend (Boolean) -> Unit,
    cacheDir: File,
): ArchivePageSource? = withContext(Dispatchers.IO) {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
    val opener = ArchiveOpener(context, pfd)
    val session = openSession(opener, passwdFlow, onPasswordRequested)
    if (session == null) {
        pfd.close()
        return@withContext null
    }
    build(session, pfd, cacheDir)
}

/** 先试已记住的密码；都不对才走 [passwdFlow] 一遍遍问，直到打开或用户放弃 */
private suspend fun openSession(
    opener: ArchiveOpener,
    passwdFlow: Flow<String?>,
    onPasswordRequested: suspend (Boolean) -> Unit,
): ArchiveSession? {
    Settings.archivePasswds.orEmpty().forEach { saved ->
        opener.attempt(saved.toCharArray()).first?.let { return it }
    }
    while (true) {
        val (opened, needPassword) = opener.attempt(null)
        if (opened != null) return opened
        if (!needPassword) return null
        onPasswordRequested(true)
        val passwd = passwdFlow.first()
        onPasswordRequested(false)
        if (passwd == null) return null
        val retried = opener.attempt(passwd.toCharArray()).first
        if (retried != null) {
            Settings.putPasswdToArchivePasswds(passwd)
            return retried
        }
        // 密码不对，回到循环再问一次
    }
}

/** 只收图片条目 + 自然序；页源关闭时顺带关掉 fd */
private fun build(session: ArchiveSession, pfd: ParcelFileDescriptor, cacheDir: File): ArchivePageSource {
    val picked = ArrayList<Pair<Int, String>>()
    session.entries.forEachIndexed { index, entry ->
        if (!entry.isDirectory && isImageEntry(entry)) picked.add(index to entry.name)
    }
    val order = picked.indices.sortedWith(Comparator { a, b -> naturalCompare(picked[a].second, picked[b].second) })
    return ArchivePageSource(session, cacheDir, order.map { picked[it].first }) { runCatching { pfd.close() } }
}

private fun ArchiveOpener.attempt(passwd: CharArray?): Pair<ArchiveSession?, Boolean> = try {
    (if (passwd == null) open() else open(passwd)) to false
} catch (e: ArchivePasswordRequiredException) {
    null to true
} catch (e: Exception) {
    null to false
}

private fun isImageEntry(entry: ArchiveEntry): Boolean = SUPPORT_IMAGE_EXTENSIONS.any { entry.name.endsWith(it, ignoreCase = true) }

/** 数字段按数值比较，其余按码位，保证 2.jpg < 10.jpg */
private fun naturalCompare(lhs: String, rhs: String): Int {
    var i = 0
    var j = 0
    while (i < lhs.length && j < rhs.length) {
        val a = lhs[i]
        val b = rhs[j]
        if (a.isDigit() && b.isDigit()) {
            var ni = i
            var nj = j
            while (ni < lhs.length && lhs[ni].isDigit()) ni++
            while (nj < rhs.length && rhs[nj].isDigit()) nj++
            val na = lhs.substring(i, ni).trimStart('0')
            val nb = rhs.substring(j, nj).trimStart('0')
            val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ni
            j = nj
        } else {
            if (a != b) return a.compareTo(b)
            i++
            j++
        }
    }
    return (lhs.length - i) - (rhs.length - j)
}

/**
 * 在线 / 缓存页源：把 `SpiderQueen` 的读模式包装成 [PageSource]。
 *
 * 两个刻意的选择：
 * 1. 用 `SpiderQueen.preloadPages` 触发下载，而不是 `request`——后者会顺带把整张图
 *    解码成 `Image`，Compose 阅读器根本用不到，白占 native 内存还要负责回收；
 * 2. 下载完成后用 `SpiderQueen.save` 把缓存 / 下载目录里的那份拷进本会话的缓存目录，
 *    给 Coil 与 telephoto 一个可随机访问的 `File`。
 *
 * 已下载画廊不需要单独分支：`SpiderQueen` 读模式本来就先读本地（`.ehviewer` 与
 * 下载目录），所以在线、已下载共用这一条路径。
 */
class OnlinePageSource(
    private val galleryInfo: GalleryInfo,
    private val cacheDir: File,
) : PageSource {
    private val queen = obtainSpiderQueen(galleryInfo, SpiderQueen.MODE_READ)
    private val lock = Any()
    private val pending = HashMap<Int, CompletableDeferred<Unit>>()
    private val progressFlow = MutableSharedFlow<PageProgress>(extraBufferCapacity = 64)

    @Volatile
    private var ready = false

    @Volatile
    private var pageCount = 0

    private val listener = object : SpiderQueen.OnSpiderListener {
        override fun onGetPages(pages: Int) {}

        override fun onGet509(index: Int) {
            finish(index, "509")
        }

        override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            // 没有 Content-Length 就没有分母，UI 那边退化成转圈
            val percent = if (contentLength > 0L) {
                (receivedSize * 100L / contentLength).toInt().coerceIn(0, 100)
            } else {
                null
            }
            progressFlow.tryEmit(PageProgress(index, percent))
        }

        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {
            finish(index, null)
        }

        override fun onPageFailure(index: Int, error: String?, finished: Int, downloaded: Int, total: Int) {
            finish(index, error)
        }

        override fun onFinish(finished: Int, downloaded: Int, total: Int) {}

        override fun onGetImageSuccess(index: Int, image: Image?) {
            image?.recycle()
        }

        override fun onGetImageFailure(index: Int, error: String?) {}
    }

    override val count: Int
        get() = if (ready) pageCount else 0

    override val startPage: Int
        get() = if (ready) queen.startPage else 0

    override val progress: Flow<PageProgress>
        get() = progressFlow

    override suspend fun prepare(): Boolean {
        if (ready) return true
        if (!queen.awaitReady()) return false
        queen.addOnSpiderListener(listener)
        pageCount = queen.size
        ready = true
        return true
    }

    override fun name(index: Int): String = (index + 1).toString()

    override fun putStartPage(page: Int) {
        if (ready) queen.putStartPage(page)
    }

    override fun invalidate(index: Int) {
        // 缓存索引与本地副本都要清，否则下一次 page() 会直接复用旧文件
        queen.mSpiderDen.remove(index)
        File(cacheDir, PAGE_NAME_FORMAT.format(Locale.US, index + 1)).delete()
    }

    override suspend fun original(index: Int, dir: File): File? = withContext(Dispatchers.IO) {
        if (!ready) return@withContext null
        val filename = IMAGE_NAME_FORMAT.format(Locale.US, galleryInfo.gid, galleryInfo.token, index + 1)
        val file = queen.downloadOriginal(index, UniFile.fromFile(dir)!!, filename) ?: return@withContext null
        // RawFile 的 uri 就是 Uri.fromFile(target)，直接拿路径
        file.uri.path?.let(::File)?.takeIf { it.isFile }
    }

    override suspend fun page(index: Int): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, PAGE_NAME_FORMAT.format(Locale.US, index + 1))
        if (target.isFile && target.length() > 0L) return@withContext target
        awaitDownloaded(index)
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${target.name}.part")
        if (!queen.save(index, UniFile.fromFile(tmp)!!) || !tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot materialize page $index")
        }
        target
    }

    override fun close() {
        queen.removeOnSpiderListener(listener)
        releaseSpiderQueen(queen, SpiderQueen.MODE_READ)
        cacheDir.deleteRecursively()
    }

    private suspend fun awaitDownloaded(index: Int) {
        val deferred = synchronized(lock) {
            pending[index] ?: CompletableDeferred<Unit>().also { pending[index] = it }
        }
        // 只下载、不解码；bounds 覆盖全页，免得把别处正在下的页取消掉
        queen.preloadPages(listOf(index), 0 to Int.MAX_VALUE)
        try {
            withTimeout(PAGE_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            synchronized(lock) { pending.remove(index) }
            throw IOException("Timeout while loading page $index", e)
        }
    }

    private fun finish(index: Int, error: String?) {
        val deferred = synchronized(lock) { pending.remove(index) } ?: return
        if (error == null) {
            deferred.complete(Unit)
        } else {
            deferred.completeExceptionally(IOException(error))
        }
    }
}

/**
 * 已下载页源：直接读下载目录，不发任何请求。
 *
 * 页数优先取 `.ehviewer` 里的 `SpiderInfo.pages`（下载还没完成时页数依然准确），
 * 取不到才按目录里 `%08d.*` 的最大序号推导。缺页仍然保留条目、到 [page] 时才报错——
 * R2 的 `Error` 条目正好接住，而不是让整个阅读器开不起来。
 */
class DownloadPageSource(
    private val dir: UniFile,
    private val cacheDir: File,
) : PageSource {
    @Volatile
    private var files: List<UniFile?> = emptyList()

    override val count: Int
        get() = files.size

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        files = scan()
        files.isNotEmpty()
    }

    override fun name(index: Int): String = files.getOrNull(index)?.name ?: (index + 1).toString()

    override suspend fun page(index: Int): File = withContext(Dispatchers.IO) {
        val target = File(cacheDir, PAGE_NAME_FORMAT.format(Locale.US, index + 1))
        if (target.isFile && target.length() > 0L) return@withContext target
        val source = files.getOrNull(index) ?: throw IOException("Page $index is not downloaded")
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${target.name}.part")
        source.openInputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot materialize page $index")
        }
        target
    }

    override fun close() {
        cacheDir.deleteRecursively()
    }

    private fun scan(): List<UniFile?> {
        val spiderInfo = dir.findFile(SpiderQueen.SPIDER_INFO_FILENAME)?.let(::readFromUniFile)
        val count = spiderInfo?.pages
            ?: dir.listFiles()?.maxOfOrNull { pageIndexOf(it.name) ?: -1 }?.let { it + 1 }
            ?: 0
        return List(count.coerceAtLeast(0)) { SpiderDen.findImageFile(dir, it) }
    }

    /** `SpiderDen.perFilename` 是「1 基序号 + 扩展名」 */
    private fun pageIndexOf(name: String?): Int? {
        val digits = name?.takeWhile { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()?.let { it - 1 }?.takeIf { it >= 0 }
    }
}

/**
 * 按 route 造页源；缓存目录由调用方给，页源关闭时整目录删掉。
 *
 * `DOWNLOAD` 拿不到下载目录时退回在线页源：旧实现走 `EhGalleryProvider`（SpiderQueen
 * 读模式）本来就是这么兜底的，不能因为下载目录被删了就打不开画廊。
 */
suspend fun readerPageSource(
    route: EhRoute.Reader,
    cacheDir: File,
    context: Context,
    passwdFlow: Flow<String?>,
    onPasswordRequested: suspend (Boolean) -> Unit,
): PageSource {
    val info = BaseGalleryInfo(gid = route.gid, token = route.token)
    return when (route.source) {
        ReaderSource.ONLINE -> OnlinePageSource(info, cacheDir)
        ReaderSource.DOWNLOAD -> withContext(Dispatchers.IO) {
            SpiderDen.getGalleryDownloadDir(route.gid)?.takeIf { it.isDirectory }
                ?.let { DownloadPageSource(it, cacheDir) }
                ?: OnlinePageSource(info, cacheDir)
        }
        ReaderSource.ARCHIVE -> openArchivePageSource(context, route.uri!!.toUri(), passwdFlow, onPasswordRequested, cacheDir)
            ?: throw IOException("Cannot open archive")
    }
}
