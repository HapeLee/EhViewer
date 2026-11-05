/*
 * Copyright 2024 Moedog
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
package io.kazusa.ehviewer.coil

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.kazusa.ehviewer.EhApplication.Companion.thumbCache
import io.kazusa.ehviewer.Settings.downloadLocation
import io.kazusa.ehviewer.spider.DownloadInfoMagics.decodeMagicRequestOrUrl
import io.kazusa.ehviewer.support.unifile.UniFile
import io.kazusa.ehviewer.support.util.sendTo
import io.kazusa.ehviewer.support.util.withIOContext

object DownloadThumbInterceptor : Interceptor {
    const val THUMB_FILE = ".thumb"
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val magicOrUrl = chain.request.data as? String
        if (magicOrUrl != null) {
            val (url, location) = decodeMagicRequestOrUrl(magicOrUrl)
            if (location != null) {
                return withIOContext {
                    val thumb = downloadLocation?.subFile(location)?.subFile(THUMB_FILE)
                    if (thumb?.isFile == true) {
                        val new = chain.request.newBuilder().data(thumb.uri).build()
                        val result = chain.withRequest(new).proceed()
                        if (result is SuccessResult) return@withIOContext result
                    }
                    val new = chain.request.newBuilder().data(url).build()
                    val result = chain.withRequest(new).proceed()
                    if (result is SuccessResult && thumb?.parentFile?.isDirectory == true) {
                        // Accessing the recreated file immediately after deleting it throws
                        // FileNotFoundException, so we just overwrite the existing file.
                        chain.request.memoryCacheKey?.let {
                            if (!thumb.exists()) thumb.ensureFile()
                            thumbCache.read(it) {
                                UniFile.fromFile(data.toFile())!! sendTo thumb
                            }
                        }
                    }
                    result
                }
            }
        }
        return chain.proceed()
    }
}
