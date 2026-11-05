package io.kazusa.ehviewer.ui.reader

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageFileCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun concurrentPrefetchAndDisplayMaterializeOnlyOnce() = runBlocking {
        val directory = temp.newFolder()
        val cache = PageFileCache(directory)
        val writes = AtomicInteger()
        val bytes = ByteArray(128 * 1024) { (it % 97).toByte() }
        val files = List(32) {
            async(Dispatchers.Default) {
                cache.get(0, "page") {
                    writes.incrementAndGet()
                    it.writeBytes(bytes)
                }
            }
        }.awaitAll()
        assertEquals(1, writes.get())
        files.forEach { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(listOf("page"), directory.list()!!.toList())
        cache.close()
    }

    @Test
    fun failedCopyCleansPartialFileAndCanRetry() = runBlocking {
        val directory = temp.newFolder()
        val cache = PageFileCache(directory)
        val failure = runCatching {
            cache.get(0, "page") {
                it.writeText("partial")
                throw IOException("copy failed")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(directory.list()!!.isEmpty())
        assertEquals("complete", cache.get(0, "page") { it.writeText("complete") }.readText())
        cache.close()
    }

    @Test
    fun closeWhileDownloadIsPendingPreventsCacheResurrection() = runBlocking {
        val directory = temp.newFolder()
        val cache = PageFileCache(directory)
        val preparing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                cache.get(0, "page", prepare = {
                    preparing.complete(Unit)
                    resume.await()
                }) { it.writeText("late") }
            }
        }
        preparing.await()
        cache.close()
        resume.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is IOException)
        assertFalse(directory.exists())
    }

    @Test
    fun refreshWaitsForInflightCopyAndDiscardsItsResult() = runBlocking {
        val directory = temp.newFolder()
        val cache = PageFileCache(directory)
        val preparing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val load = async {
            cache.get(0, "page", prepare = {
                preparing.complete(Unit)
                resume.await()
            }) { it.writeText("old") }
        }
        preparing.await()
        val refresh = async { cache.invalidate(0, "page") }
        resume.complete(Unit)
        load.await()
        refresh.await()
        assertEquals("new", cache.get(0, "page") { it.writeText("new") }.readText())
        cache.close()
    }
}
