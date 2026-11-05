package com.hippo.ehviewer.ui.reader

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDownloadWaiterTest {
    @Test
    fun alreadyDownloadedPageDoesNotWaitForAnotherCallback() = runBlocking {
        val waiter = PageDownloadWaiter(1_000)
        waiter.await(0, isDownloaded = { true }, request = { error("Must use the downloaded page") })
        waiter.close()
    }

    @Test
    fun completionDuringStateCheckIsNotLost() = runBlocking {
        val waiter = PageDownloadWaiter(1_000)
        waiter.await(0, isDownloaded = {
            waiter.finish(0, null)
            false
        }, request = {})
        waiter.close()
    }

    @Test
    fun failureCanBeRetriedWithANewWaiter() = runBlocking {
        val waiter = PageDownloadWaiter(1_000)
        val failure = runCatching {
            waiter.await(0, isDownloaded = { false }, request = { waiter.finish(0, "network failure") })
        }.exceptionOrNull()
        assertEquals("network failure", failure?.message)
        waiter.await(0, isDownloaded = { false }, request = { waiter.finish(0, null) })
        waiter.close()
    }

    @Test
    fun closeReleasesPendingAndRejectsNewRequests() = runBlocking {
        val waiter = PageDownloadWaiter(60_000)
        val requested = CompletableDeferred<Unit>()
        val result = async {
            runCatching { waiter.await(0, isDownloaded = { false }, request = { requested.complete(Unit) }) }
        }
        requested.await()
        waiter.close()
        assertTrue(result.await().exceptionOrNull() is IOException)
        assertTrue(runCatching { waiter.await(1, { true }, {}) }.exceptionOrNull() is IOException)
    }

    @Test
    fun cancellingARequestDoesNotLeaveAStaleWaiter() = runBlocking {
        val waiter = PageDownloadWaiter(60_000)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { waiter.await(0, { false }, {}) }
        cancelled.cancelAndJoin()
        waiter.await(0, { false }, { waiter.finish(0, null) })
        waiter.close()
    }

    @Test
    fun timeoutDoesNotPreventRetryingThePage() = runBlocking {
        val waiter = PageDownloadWaiter(10)
        assertTrue(runCatching { waiter.await(0, { false }, {}) }.exceptionOrNull() is IOException)
        waiter.await(0, { false }, { waiter.finish(0, null) })
        waiter.close()
    }
}
