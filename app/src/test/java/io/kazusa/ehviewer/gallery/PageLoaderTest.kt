package io.kazusa.ehviewer.gallery

import io.kazusa.ehviewer.image.ImageSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLoaderTest {
    @Test
    fun retryInvalidatesTheSourceAndReportsMissingData() = runBlocking {
        val host = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var invalidated = -1
        val loader = object : PageLoader(host, 0, 1) {
            override suspend fun invalidateSource(index: Int) {
                invalidated = index
            }

            override suspend fun openSource(index: Int, original: Boolean): ImageSource? = null
        }
        try {
            loader.retryPage(0)
            withTimeout(5_000) { loader.pages[0].statusFlow.first { it is PageStatus.Error } }
            assertEquals(0, invalidated)
            assertTrue((loader.pages[0].status as PageStatus.Error).message!!.contains("No source"))
        } finally {
            loader.close()
            host.cancel()
        }
    }

    @Test
    fun retryCancelsThePreviousLoadAndStartsANewOne() = runBlocking {
        val host = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var attempts = 0
        val loader = object : PageLoader(host, 0, 1) {
            override suspend fun openSource(index: Int, original: Boolean): ImageSource? {
                if (++attempts == 1) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                return null
            }
        }
        try {
            loader.retryPage(0)
            withTimeout(5_000) { started.await() }
            loader.retryPage(0)
            withTimeout(5_000) {
                cancelled.await()
                loader.pages[0].statusFlow.first { it is PageStatus.Error }
            }
            assertEquals(2, attempts)
        } finally {
            loader.close()
            host.cancel()
        }
    }

    @Test
    fun hostCancellationStopsPendingLoads() = runBlocking {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val loader = object : PageLoader(CoroutineScope(Dispatchers.Default + parent), 0, 1) {
            override suspend fun openSource(index: Int, original: Boolean): ImageSource? {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        try {
            loader.retryPage(0)
            withTimeout(5_000) { started.await() }
            parent.cancel()
            withTimeout(5_000) { cancelled.await() }
        } finally {
            parent.cancel()
            loader.close()
        }
    }

    @Test
    fun lateProgressDoesNotReplaceAnErrorWithAnEndlessSpinner() {
        val host = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val loader = object : PageLoader(host, 0, 1) {
            override suspend fun openSource(index: Int, original: Boolean): ImageSource? = null
        }
        try {
            loader.notifyPageFailed(0, "network failure")
            loader.notifyPagePercent(0, 0.5f)
            assertEquals(PageStatus.Error("network failure"), loader.pages[0].status)
        } finally {
            loader.close()
            host.cancel()
        }
    }
}
