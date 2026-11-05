package com.hippo.ehviewer.spider

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageTransferTest {
    private fun body(bytes: String, length: Long): ResponseBody = object : ResponseBody() {
        private val data = Buffer().writeUtf8(bytes)
        override fun contentType() = "image/jpeg".toMediaType()
        override fun contentLength() = length
        override fun source() = data
    }

    @Test
    fun acceptsAnImageWithoutContentLength() {
        val output = Buffer()
        var reportedLength = 0L
        assertEquals(5L, copyImageBody(body("image", -1), output) { length, _, _ -> reportedLength = length })
        assertEquals("image", output.readUtf8())
        assertEquals(-1L, reportedLength)
    }

    @Test
    fun acceptsACompleteKnownLengthImage() {
        val output = Buffer()
        assertEquals(5L, copyImageBody(body("image", 5), output))
        assertEquals("image", output.readUtf8())
    }

    @Test
    fun rejectsTruncatedImagesBeforeTheyCanBeCommitted() {
        assertThrows(IOException::class.java) { copyImageBody(body("short", 100), Buffer()) }
    }

    @Test
    fun rejectsEmptyUnknownLengthImages() {
        assertThrows(IOException::class.java) { copyImageBody(body("", -1), Buffer()) }
    }

    @Test
    fun rejectsEmptyKnownLengthImages() {
        assertThrows(IOException::class.java) { copyImageBody(body("", 0), Buffer()) }
    }
}
