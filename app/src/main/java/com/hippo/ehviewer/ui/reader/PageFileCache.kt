package com.hippo.ehviewer.ui.reader

import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes requests for the same page, including prefetch and image actions. */
internal class PageFileCache(private val directory: File) : Closeable {
    private val lock = Any()
    private val pages = HashMap<Int, Mutex>()
    private var closed = false

    suspend fun get(
        index: Int,
        filename: String,
        prepare: suspend () -> Unit = {},
        write: (File) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val mutex = synchronized(lock) { pages.getOrPut(index) { Mutex() } }
        mutex.withLock {
            val target = File(directory, filename)
            synchronized(lock) {
                checkOpen()
                if (target.isFile && target.length() > 0L) return@withContext target
            }
            prepare()
            currentCoroutineContext().ensureActive()
            // close() cannot remove the directory or close an archive during a copy.
            synchronized(lock) {
                checkOpen()
                directory.mkdirs()
                val temporary = File(directory, "$filename.part")
                try {
                    write(temporary)
                    if (temporary.length() == 0L || !temporary.renameTo(target)) {
                        throw IOException("Cannot materialize page $index")
                    }
                    target
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    suspend fun invalidate(index: Int, filename: String, invalidateSource: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val mutex = synchronized(lock) { pages.getOrPut(index) { Mutex() } }
        mutex.withLock {
            synchronized(lock) {
                checkOpen()
                File(directory, filename).delete()
                invalidateSource()
            }
        }
    }

    private fun checkOpen() {
        if (closed) throw IOException("Page source closed")
    }

    fun close(release: () -> Unit) = synchronized(lock) {
        if (!closed) {
            closed = true
            try {
                release()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    override fun close() = close {}
}
