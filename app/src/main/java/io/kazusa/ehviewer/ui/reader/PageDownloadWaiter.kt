package io.kazusa.ehviewer.ui.reader

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Registers a callback waiter before inspecting or requesting a download. */
internal class PageDownloadWaiter(private val timeoutMillis: Long) : Closeable {
    private val lock = Any()
    private val pending = HashMap<Int, CompletableDeferred<Unit>>()
    private var closed = false

    suspend fun await(index: Int, isDownloaded: () -> Boolean, request: () -> Unit) {
        val deferred = synchronized(lock) {
            if (closed) throw IOException("Page source closed")
            pending.getOrPut(index) { CompletableDeferred() }
        }
        try {
            if (isDownloaded()) {
                deferred.complete(Unit)
            } else {
                request()
            }
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timeout while loading page $index", e)
        } finally {
            synchronized(lock) { if (pending[index] === deferred) pending.remove(index) }
        }
    }

    fun finish(index: Int, error: String?) {
        val deferred = synchronized(lock) { pending.remove(index) } ?: return
        if (error == null) deferred.complete(Unit) else deferred.completeExceptionally(IOException(error))
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending.values.forEach { it.completeExceptionally(IOException("Page source closed")) }
        pending.clear()
    }
}
