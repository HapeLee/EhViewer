/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kazusa.ehviewer.spider

import android.util.Log
import io.kazusa.ehviewer.EhApplication.Companion.imageCache as sCache
import io.kazusa.ehviewer.EhApplication.Companion.nonCacheOkHttpClient
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhRequestBuilder
import io.kazusa.ehviewer.client.EhUtils.getSuitableTitle
import io.kazusa.ehviewer.client.data.GalleryInfo
import io.kazusa.ehviewer.client.getImageKey
import io.kazusa.ehviewer.coil.edit
import io.kazusa.ehviewer.coil.read
import io.kazusa.ehviewer.download.DownloadManager
import io.kazusa.ehviewer.support.image.UniFileSource
import io.kazusa.ehviewer.support.unifile.UniFile
import io.kazusa.ehviewer.support.unifile.openOutputStream
import io.kazusa.ehviewer.support.unifile.sha1
import io.kazusa.ehviewer.support.util.runInterruptibleOkio
import io.kazusa.ehviewer.support.util.runSuspendCatching
import io.kazusa.ehviewer.support.util.sendTo
import io.kazusa.ehviewer.support.yorozuya.FileUtils
import io.kazusa.ehviewer.support.yorozuya.cleanAsDirname
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.buffer
import okio.sink

class SpiderDen(private val mGalleryInfo: GalleryInfo) {
    private val fileHashRegex = Regex("/h/([0-9a-f]{40})")
    private val mGid = mGalleryInfo.gid
    var downloadDir: UniFile? = null

    @Volatile
    @SpiderQueen.Mode
    var mode = SpiderQueen.MODE_READ
        set(value) {
            field = value
            if (field == SpiderQueen.MODE_DOWNLOAD && downloadDir == null) {
                val title = getSuitableTitle(mGalleryInfo)
                val dirname = FileUtils.sanitizeFilename("$mGid-$title")
                downloadDir = perSafeDownloadDir(mGid, dirname)
            }
        }

    private fun containInCache(index: Int): Boolean {
        val key = getImageKey(mGid, index)
        return sCache.openSnapshot(key)?.use { true } == true
    }

    private fun containInDownloadDir(index: Int): Boolean {
        val dir = downloadDir ?: return false
        return findImageFile(dir, index) != null
    }

    private fun copyFromCacheToDownloadDir(index: Int, skip: Boolean): Boolean {
        val dir = downloadDir ?: return false
        // Find image file in cache
        val key = getImageKey(mGid, index)
        return runCatching {
            sCache.read(key) {
                // Get extension
                val extension = fixExtension("." + metadata.toFile().readText())
                // Don't copy from cache if `download original image` enabled, ignore gif
                if (skip && extension != GIF_IMAGE_EXTENSION) {
                    return false
                }
                // Copy from cache to download dir
                val file = dir.createFile(perFilename(index, extension)) ?: return false
                UniFile.fromFile(data.toFile())!! sendTo file
            }
        }.getOrElse {
            Log.e(TAG, "Error", it)
            false
        }
    }

    fun copyFromUniFileToDownloadDir(oldDir: UniFile, oldIndex: Int, index: Int): Boolean {
        val dir = downloadDir ?: return false
        return runCatching {
            val oldFile = findImageFile(oldDir, oldIndex) ?: return false
            val extension = oldFile.name.let { name -> FileUtils.getExtensionFromFilename(name) }
            val file = dir.createFile(perFilename(index, ".$extension")) ?: return false
            oldFile sendTo file
            true
        }.getOrElse {
            Log.e(TAG, "Error", it)
            false
        }
    }

    operator fun contains(index: Int): Boolean = when (mode) {
        SpiderQueen.MODE_READ -> {
            containInCache(index) || containInDownloadDir(index)
        }
        SpiderQueen.MODE_DOWNLOAD -> {
            containInDownloadDir(index) || copyFromCacheToDownloadDir(index, Settings.skipCopyImage)
        }
        else -> {
            false
        }
    }

    private fun removeFromCache(index: Int): Boolean {
        val key = getImageKey(mGid, index)
        return sCache.remove(key)
    }

    private fun removeFromDownloadDir(index: Int): Boolean = downloadDir?.let { findImageFile(it, index)?.delete() } == true

    fun remove(index: Int): Boolean = removeFromCache(index) or removeFromDownloadDir(index)

    private fun findDownloadFileForIndex(index: Int, extension: String): UniFile? {
        val dir = downloadDir ?: return null
        val ext = fixExtension(".$extension")
        return dir.createFile(perFilename(index, ext))
    }

    @Throws(IOException::class)
    suspend fun saveImageFromUrl(
        url: String,
        referer: String?,
        dir: UniFile,
        filename: String,
    ): UniFile? {
        nonCacheOkHttpClient.newCall(EhRequestBuilder(url, referer).build()).executeAsync().use {
            if (it.code >= 400) return null
            val ext = it.body.contentType()?.subtype ?: "jpg"
            val dst = dir.subFile("$filename.$ext") ?: return null
            return runSuspendCatching {
                runInterruptibleOkio {
                    dst.openOutputStream().sink().buffer().use { sink ->
                        copyImageBody(it.body, sink)
                    }
                }
                dst
            }.onFailure { e ->
                dst.delete()
                Log.e(TAG, "Error", e)
            }.getOrNull()
        }
    }

    @Throws(IOException::class)
    suspend fun makeHttpCallAndSaveImage(
        index: Int,
        url: String,
        referer: String?,
        notifyProgress: (Long, Long, Int) -> Unit,
    ): Boolean {
        // TODO: Use HttpEngine[https://developer.android.com/reference/android/net/http/HttpEngine] directly here if available
        // Since we don't want unnecessary copy between jvm heap & native heap
        nonCacheOkHttpClient.newCall(EhRequestBuilder(url, referer).build()).executeAsync().use {
            if (it.code >= 400) return false
            return saveFromHttpResponse(index, it, notifyProgress)
        }
    }

    private suspend fun saveFromHttpResponse(index: Int, response: Response, notifyProgress: (Long, Long, Int) -> Unit): Boolean {
        val url = response.request.url.toString()
        val extension = response.body.contentType()?.subtype ?: "jpg"

        suspend fun doSave(outFile: UniFile) {
            runInterruptibleOkio {
                outFile.openOutputStream().sink().buffer().use { sink ->
                    copyImageBody(response.body, sink, notifyProgress)
                }
                fileHashRegex.find(url)?.let {
                    val expected = it.groupValues[1]
                    val actual = outFile.sha1()
                    check(expected == actual) { "File hash mismatch: expected $expected, but got $actual\nURL: $url" }
                }
            }
        }

        findDownloadFileForIndex(index, extension)?.let { target ->
            return target.runSuspendCatching {
                doSave(this)
                true
            }.onFailure {
                target.delete()
                Log.e(TAG, "Error", it)
            }.getOrDefault(false)
        }

        // Validate before committing so a truncated response never becomes a cache hit.
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(mGid, index)
            return runSuspendCatching {
                sCache.edit(key) {
                    metadata.toFile().writeText(extension)
                    doSave(UniFile.fromFile(data.toFile())!!)
                }
            }.onFailure {
                Log.e(TAG, "Error", it)
            }.getOrDefault(false)
        }

        return false
    }

    fun saveToUniFile(index: Int, file: UniFile): Boolean {
        val key = getImageKey(mGid, index)

        // Read from diskCache first
        sCache.read(key) {
            runCatching {
                UniFile.fromFile(data.toFile())!! sendTo file
                return true
            }.onFailure {
                Log.e(TAG, "Error", it)
                return false
            }
        }

        // Read from download dir
        runCatching {
            findImageFile(downloadDir!!, index)!! sendTo file
        }.onFailure {
            Log.e(TAG, "Error", it)
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun getExtension(index: Int): String? {
        val key = getImageKey(mGid, index)
        return sCache.openSnapshot(key)?.use { it.metadata.toFile().readText() }
            ?: downloadDir?.let { findImageFile(it, index) }
                ?.name.let { FileUtils.getExtensionFromFilename(it) }
    }

    fun getImageSource(index: Int): UniFileSource? {
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(mGid, index)
            sCache.openSnapshot(key)?.let {
                val source = UniFile.fromFile(it.data.toFile())!!
                return object : UniFileSource, AutoCloseable by it {
                    override val source = source
                }
            }
        }
        val dir = downloadDir ?: return null
        val source = findImageFile(dir, index) ?: return null
        return object : UniFileSource {
            override val source = source
            override fun close() {}
        }
    }

    companion object {
        private val TAG = SpiderDen::class.java.simpleName

        /**
         * 支持的图片扩展名（带点）。
         *
         * 原来挂在 `GalleryProvider2` 上，旧 GL 栈删掉之后搬到这里——压缩包页源也要用。
         */
        val SUPPORT_IMAGE_EXTENSIONS = arrayOf(
            ".jpg",
            ".png",
            ".gif",
            ".webp",
        )

        private val COMPAT_IMAGE_EXTENSIONS = SUPPORT_IMAGE_EXTENSIONS + ".jpeg"
        private val GIF_IMAGE_EXTENSION = SUPPORT_IMAGE_EXTENSIONS[2]

        /**
         * @param extension with dot
         */
        private fun fixExtension(extension: String): String = extension.takeIf { SUPPORT_IMAGE_EXTENSIONS.contains(it) }
            ?: SUPPORT_IMAGE_EXTENSIONS[0]

        fun findImageFile(dir: UniFile, index: Int): UniFile? = COMPAT_IMAGE_EXTENSIONS.firstNotNullOfOrNull { dir.findFile(perFilename(index, it)) }

        /**
         * @param extension with dot
         */
        fun perFilename(index: Int, extension: String?): String = String.format(Locale.US, "%08d%s", index + 1, extension)

        fun getGalleryDownloadDir(gid: Long): UniFile? {
            val dir = Settings.downloadLocation ?: return null
            val dirname = DownloadManager.getDownloadDirname(gid) ?: return null
            return dir.subFile(dirname)
        }

        private fun perDownloadDir(gid: Long, dirname: String): UniFile? {
            // 内存态必须先于建目录生效，所以这里不能火后不管，只能 runBlocking 桥一下。
            // DB 的写仍在 IO 线程（putDownloadDirname 内部 withIOContext），
            // 与改造前「直接在本线程写库」的阻塞量级一致。P6-c 会把整条链换成协程
            runBlocking { DownloadManager.putDownloadDirname(gid, dirname) }
            return getGalleryDownloadDir(gid)?.takeIf { it.ensureDir() }
        }

        fun perSafeDownloadDir(gid: Long, dirname: String): UniFile? = perDownloadDir(gid, dirname) ?: perDownloadDir(gid, dirname.cleanAsDirname())
    }
}
