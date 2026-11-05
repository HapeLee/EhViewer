package com.hippo.ehviewer.spider

import java.io.IOException
import okhttp3.ResponseBody
import okio.BufferedSink

/** EOF completes an unknown-length response; known lengths must still match. */
internal fun copyImageBody(
    body: ResponseBody,
    sink: BufferedSink,
    notifyProgress: (Long, Long, Int) -> Unit = { _, _, _ -> },
): Long {
    val length = body.contentLength()
    var received = 0L
    body.source().use { source ->
        while (true) {
            val count = source.read(sink.buffer, 8192)
            if (count == -1L) break
            received += count
            sink.emitCompleteSegments()
            notifyProgress(length, received, count.toInt())
        }
    }
    if (received == 0L || length >= 0L && received != length) {
        throw IOException("Incomplete image: received $received bytes, expected $length")
    }
    return received
}
