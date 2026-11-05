/*
 * Copyright 2022 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with EhViewer. If not, see <https://www.gnu.org/licenses/>.
 */
package io.kazusa.ehviewer.gallery

import androidx.collection.LruCache
import io.kazusa.ehviewer.EhDB
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.image.Image
import io.kazusa.ehviewer.image.ImageSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private val progressScope = CoroutineScope(Dispatchers.IO)

private const val MAX_CACHE_SIZE = 128 * 1024 * 1024
private const val MIN_CACHE_SIZE = 16 * 1024 * 1024

private fun cacheSize(): Int {
    val total = Runtime.getRuntime().maxMemory()
    return (total / 8).coerceIn(MIN_CACHE_SIZE.toLong(), MAX_CACHE_SIZE.toLong()).toInt()
}

/**
 * 页数据管线：请求 → 解码 → 放进内存缓存 → 通知 [Page]。
 *
 * 结构照 EHz 的 `PageLoader`（原来的 arrow `bracketCase` 换成 try/finally），
 * 数据获取下沉到 [openSource]，所以在线 / 下载 / 压缩包三种来源由子类决定。
 */
abstract class PageLoader(
    scope: CoroutineScope,
    startPage: Int,
    val size: Int,
    private val gid: Long = -1L,
) : AutoCloseable {

    var startPage: Int = startPage.coerceIn(0, (size - 1).coerceAtLeast(0))

    val pages: List<Page> = List(size) { Page(it) }

    protected val loadScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val jobs = HashMap<Int, Job>()
    private val mutexes = HashMap<Int, Mutex>()
    private val semaphore = Semaphore(4)
    private val prevIndex = AtomicInteger(-1)
    private val cacheLock = Any()
    private val imageCacheSize = cacheSize()

    private val cache = object : LruCache<Int, Image>(imageCacheSize) {
        // Keep a single oversized page until the UI can acquire its reference.
        override fun sizeOf(key: Int, value: Image): Int = value.allocationSize.coerceIn(1, imageCacheSize)

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Image, newValue: Image?) {
            if (oldValue === newValue) return
            // 缓存这一份引用放开后没人再 pin，就说明这一页可以从内存里退掉
            if (oldValue.unpin()) resetIfHolding(key, oldValue)
        }
    }

    private val prefetchPageCount: Int get() = Settings.readerPreloadImage.value

    var title: String = ""
        protected set

    /** 打开第 [index] 页的数据源；[original] 为 true 表示要原图 */
    protected abstract suspend fun openSource(index: Int, original: Boolean): ImageSource?

    /** 预取邻居页（只落盘不解码），默认不做事 */
    protected open fun prefetchPages(pages: List<Int>) = Unit

    /** 关闭时释放底层资源（页源、解压会话、临时目录） */
    protected open fun onClose() = Unit

    protected open suspend fun invalidateSource(index: Int) = Unit

    fun request(index: Int) {
        if (closed) return
        val prefetchRange = if (index >= prevIndex.get()) {
            (index + 1)..(index + prefetchPageCount).coerceAtMost(size - 1)
        } else {
            (index - 1) downTo (index - prefetchPageCount).coerceAtLeast(0)
        }
        prevIndex.set(index)

        val hit = synchronized(cacheLock) {
            cache[index]?.let {
                notifyPageSucceed(index, it, replaceCache = false)
                true
            } ?: false
        }
        if (!hit) {
            notifyPageWait(index)
            notifySourceReady(index)
        }

        val absent = prefetchRange.filter {
            when (pages[it].status) {
                PageStatus.Queued, is PageStatus.Error -> true
                else -> false
            }
        }
        if (absent.isNotEmpty()) prefetchPages(absent)
    }

    fun cancelRequest(index: Int) {
        synchronized(jobs) { jobs[index]?.cancel() }
    }

    fun retryPage(index: Int, original: Boolean = false) {
        if (closed) return
        cancelRequest(index)
        notifyPageWait(index)
        synchronized(cacheLock) { cache.remove(index) }
        loadPage(index, original = original, invalidate = !original)
    }

    /** 裁剪开关之类的设置变化后，所有页要重新解一遍 */
    fun restart() {
        synchronized(jobs) { jobs.values.forEach(Job::cancel) }
        synchronized(cacheLock) { cache.evictAll() }
        pages.forEach(Page::reset)
    }

    fun notifyPageWait(index: Int) = pages[index].reset()

    fun notifyPagePercent(index: Int, percent: Float) {
        pages[index].statusFlow.update {
            when (it) {
                is PageStatus.Loading -> it.apply { progress.value = percent }
                PageStatus.Queued -> PageStatus.Loading(MutableStateFlow(percent))
                else -> it
            }
        }
    }

    fun notifyPageSucceed(index: Int, image: Image, replaceCache: Boolean = true) {
        synchronized(cacheLock) {
            if (closed) {
                if (replaceCache) image.unpin()
                return
            }
            if (replaceCache) cache.put(index, image)
            pages[index].statusFlow.update { PageStatus.Ready(image) }
        }
    }

    fun notifyPageFailed(index: Int, error: String?) {
        pages[index].statusFlow.update { PageStatus.Error(error) }
    }

    open fun imageFilename(index: Int): String? = null

    /** 打开某一页的数据源并解码；同一页并发请求会被合并成一次 */
    fun notifySourceReady(index: Int) {
        loadPage(index)
    }

    private fun loadPage(index: Int, original: Boolean = false, invalidate: Boolean = false) {
        synchronized(jobs) {
            if (jobs[index]?.isActive == true) return
            jobs[index] = loadScope.launch {
                try {
                    mutexFor(index).withLock {
                        semaphore.withPermit {
                            if (invalidate) invalidateSource(index)
                            decodeInto(index, original)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notifyPageFailed(index, e.message ?: e.toString())
                }
            }
        }
    }

    private suspend fun decodeInto(index: Int, original: Boolean) {
        val src = openSource(index, original) ?: throw IOException("No source for page $index")
        try {
            val image = Image.decode(src)
            try {
                currentCoroutineContext().ensureActive()
                notifyPageSucceed(index, image)
            } catch (e: Throwable) {
                image.unpin()
                throw e
            }
        } catch (e: Throwable) {
            src.close()
            throw e
        }
    }

    private fun mutexFor(index: Int): Mutex = synchronized(mutexes) {
        mutexes.getOrPut(index) { Mutex() }
    }

    fun resetIfHolding(index: Int, image: Image) {
        pages[index].statusFlow.update {
            if (it is PageStatus.Ready && it.image === image) PageStatus.Queued else it
        }
    }

    @Volatile
    private var closed = false

    override fun close() {
        // 退出按钮与组合销毁都会调 close，落进度只能做一次
        synchronized(cacheLock) {
            if (closed) return
            closed = true
            cache.evictAll()
        }
        synchronized(jobs) {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
        }
        loadScope.cancel()
        if (gid != -1L) {
            val page = startPage
            progressScope.launch { runCatching { EhDB.putReadingProgress(gid, page) } }
        }
        runCatching { onClose() }
    }
}
