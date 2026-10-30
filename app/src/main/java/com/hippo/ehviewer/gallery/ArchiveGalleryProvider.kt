/*
 * Copyright 2023 Tarsin Norbin
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
package com.hippo.ehviewer.gallery

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hippo.ehviewer.GetText
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.archive.ArchiveEntry
import com.hippo.ehviewer.archive.ArchiveOpener
import com.hippo.ehviewer.archive.ArchivePasswordRequiredException
import com.hippo.ehviewer.archive.ArchiveSession
import com.hippo.ehviewer.archive.UnsupportedArchiveException
import com.hippo.ehviewer.gallery.GalleryProvider2.Companion.SUPPORT_IMAGE_EXTENSIONS
import com.hippo.image.ByteBufferSource
import com.hippo.image.Image
import com.hippo.unifile.UniFile
import com.hippo.unifile.openOutputStream
import com.hippo.yorozuya.FileUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ArchiveGalleryProvider(context: Context, private val uri: Uri, passwdFlow: Flow<String>) :
    GalleryProvider2(),
    CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()
    private lateinit var pfd: ParcelFileDescriptor
    private var session: ArchiveSession? = null

    /** 打开失败时给 UI 的可读原因（不支持 / 损坏 / 取消输入密码） */
    var errorMessage: String? = null
        private set

    /** 页面 → 压缩包条目下标，过滤与排序后两者不再一一对应 */
    private val archiveIndex = ArrayList<Int>()
    private val pages = ArrayList<ArchiveEntry>()
    private val mJobMap = hashMapOf<Int, Job>()

    // 7z 固实包与 TAR 都不允许并发读不同条目，这里整体串行化
    private val mEntryMutex = Mutex()

    private val hostJob = launch(start = CoroutineStart.LAZY) {
        Log.d(DEBUG_TAG, "Open archive $uri")
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        if (descriptor == null) {
            errorMessage = "Cannot open file"
            size = 0
            return@launch
        }
        pfd = descriptor
        val opener = ArchiveOpener(context, pfd)
        val opened = try {
            opener.open()
        } catch (e: ArchivePasswordRequiredException) {
            openWithPassword(opener, passwdFlow)
        } catch (e: UnsupportedArchiveException) {
            errorMessage = e.message
            Log.e(DEBUG_TAG, "Unsupported archive: $uri", e)
            null
        } catch (e: Exception) {
            errorMessage = e.message
            Log.e(DEBUG_TAG, "Failed to open archive: $uri", e)
            null
        }

        session = opened
        if (opened != null) {
            opened.entries.forEachIndexed { index, entry ->
                if (isImageEntry(entry)) {
                    archiveIndex.add(index)
                    pages.add(entry)
                }
            }
            // 自然排序：2.jpg 排在 10.jpg 前面
            val order = pages.indices.sortedWith(naturalOrder())
            val sortedIndices = order.map { archiveIndex[it] }
            val sortedPages = order.map { pages[it] }
            archiveIndex.clear()
            archiveIndex.addAll(sortedIndices)
            pages.clear()
            pages.addAll(sortedPages)
        }
        size = pages.size
    }

    override var size = -1

    override fun start() {
        hostJob.start()
    }

    override fun stop() {
        cancel()
        runCatching { session?.close() }
        session = null
        if (::pfd.isInitialized) runCatching { pfd.close() }
        Log.d(DEBUG_TAG, "Close archive $uri successfully!")
        super.stop()
    }

    private suspend fun openWithPassword(opener: ArchiveOpener, passwdFlow: Flow<String>): ArchiveSession? {
        Settings.archivePasswds.orEmpty().forEach { passwd ->
            runCatching { opener.open(passwd.toCharArray()) }.getOrNull()?.let { return it }
        }
        var result: ArchiveSession? = null
        passwdFlow.takeWhile { result == null }.collect { passwd ->
            val opened = runCatching { opener.open(passwd.toCharArray()) }.getOrNull()
            if (opened != null) {
                Settings.putPasswdToArchivePasswds(passwd)
                result = opened
            }
        }
        return result
    }

    override fun onRequest(index: Int) {
        notifyPageWait(index)
        synchronized(mJobMap) {
            val current = mJobMap[index]
            if (current?.isActive != true) {
                mJobMap[index] = launch {
                    mEntryMutex.withLock { doRealWork(index) }
                }
            }
        }
    }

    private suspend fun doRealWork(index: Int) {
        val session = session ?: return
        val buffer = try {
            readEntry(session, index)
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Extract $index failed", e)
            return notifyPageFailed(index, GetText.getString(R.string.error_reading_failed))
        } ?: return notifyPageFailed(index, GetText.getString(R.string.error_reading_failed))

        val src = object : ByteBufferSource {
            override val source: ByteBuffer = buffer
            override fun close() {}
        }
        currentCoroutineContext().ensureActive()
        val image = Image.decode(src) ?: return notifyPageFailed(index, GetText.getString(R.string.error_decoding_failed))
        currentCoroutineContext().ensureActive()
        notifyPageSucceed(index, image)
    }

    /**
     * 解到 direct buffer：Image.decode 通过 ImageDecoder 读 direct 内存，普通堆数组会被再拷一次。
     * 现阶段先整块读进内存（单页图一般几 MB），大图流式化留到 Compose Reader。
     */
    private fun readEntry(session: ArchiveSession, index: Int): ByteBuffer? = synchronized(this) {
        val expected = pages.getOrNull(index)?.size ?: 0L
        val out = ByteArrayOutputStream(if (expected in 1..Int.MAX_VALUE.toLong()) expected.toInt() else 32 * 1024)
        session.openEntry(archiveIndex[index]).use { input ->
            input.copyTo(out, DEFAULT_BUFFER_SIZE)
        }
        val bytes = out.toByteArray()
        ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
    }

    override fun onForceRequest(index: Int) {
        onRequest(index)
    }

    override suspend fun awaitReady(): Boolean {
        hostJob.join()
        return size != -1
    }

    override val isReady: Boolean
        get() = size != -1

    override fun onCancelRequest(index: Int) {
        mJobMap[index]?.cancel()
    }

    override fun getImageFilename(index: Int): String = FileUtils.getNameFromFilename(getImageFilenameWithExtension(index))

    override fun getImageFilenameWithExtension(index: Int): String = FileUtils.sanitizeFilename(pages[index].name)

    override fun save(index: Int, file: UniFile): Boolean = runCatching {
        val session = session ?: return false
        file.openOutputStream().use { out ->
            session.openEntry(archiveIndex[index]).use { input ->
                input.copyTo(out, DEFAULT_BUFFER_SIZE)
            }
        }
        true
    }.getOrElse {
        Log.e(DEBUG_TAG, "Error", it)
        false
    }

    override fun save(index: Int, dir: UniFile, filename: String): UniFile? {
        val extension = FileUtils.getExtensionFromFilename(getImageFilenameWithExtension(index))
        val dst = dir.subFile(if (null != extension) "$filename.$extension" else filename) ?: return null
        save(index, dst)
        return dst
    }

    override suspend fun downloadOriginal(index: Int, dir: UniFile, filename: String): UniFile? = null

    override fun preloadPages(pages: List<Int>, pair: Pair<Int, Int>) {}

    private fun isImageEntry(entry: ArchiveEntry): Boolean {
        if (entry.isDirectory) return false
        return SUPPORT_IMAGE_EXTENSIONS.any { entry.name.endsWith(it, ignoreCase = true) }
    }

    /** 数字段按数值比较，其余按码位，保证 2.jpg < 10.jpg */
    private fun naturalOrder(): Comparator<Int> = Comparator { a, b ->
        naturalCompare(pages[a].name, pages[b].name)
    }

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
}

private const val DEBUG_TAG = "ArchiveGalleryProvider"
