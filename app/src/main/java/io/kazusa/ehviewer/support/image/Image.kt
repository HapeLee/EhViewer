/*
 * Copyright 2022 Tarsin Norbin
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
package io.kazusa.ehviewer.support.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.util.Log
import androidx.core.graphics.createBitmap
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.asImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.SizeResolver
import io.kazusa.ehviewer.EhApplication
import io.kazusa.ehviewer.support.unifile.UniFile
import java.nio.ByteBuffer

class Image private constructor(
    private val image: CoilImage,
    private val src: ImageSource? = null,
) {
    private var mBitmap: Bitmap? = null
    private var mCanvas: Canvas? = null

    val animated get() = image is DrawableImage && image.drawable is Animatable
    val delay get() = if (animated) 40 else 0
    val isOpaque get() = false
    val width get() = image.width
    val height get() = image.height
    var frameUpdateAllowed = true
    var isRecycled = false
        private set
    var started = false
        private set

    @Synchronized
    fun recycle() {
        if (isRecycled) return
        when (image) {
            is DrawableImage -> {
                (image.drawable as? Animatable)?.stop()
                image.drawable.callback = null
                src?.close()
                mCanvas = null
                mBitmap?.recycle()
                mBitmap = null
            }
            is BitmapImage -> image.bitmap.recycle()
        }
        isRecycled = true
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        mBitmap = createBitmap(width, height)
        mCanvas = Canvas(mBitmap!!)
    }

    private fun updateBitmap() {
        if (frameUpdateAllowed) {
            frameUpdateAllowed = false
            prepareBitmap()
            mCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            image.draw(mCanvas!!)
        }
    }

    fun start() {
        if (!started) {
            started = true
            if (image is DrawableImage) (image.drawable as? Animatable)?.start()
        }
    }

    companion object {
        private val appCtx = EhApplication.application
        private val sizeResolver = with(appCtx.resources.displayMetrics) {
            val targetSize = minOf(widthPixels, heightPixels) * 4 / 3
            SizeResolver(Size(targetSize, targetSize))
        }

        private suspend fun decodeCoil(data: Any): CoilImage {
            val req = ImageRequest.Builder(appCtx).apply {
                data(data)
                size(sizeResolver)
                scale(Scale.FILL)
                precision(Precision.INEXACT)
                maxBitmapSize(Size.ORIGINAL)
                allowHardware(false)
                memoryCachePolicy(CachePolicy.DISABLED)
            }.build()
            return when (val result = appCtx.imageLoader.execute(req)) {
                is SuccessResult -> result.image
                is ErrorResult -> throw result.throwable
            }
        }

        suspend fun decode(src: ImageSource): Image? = runCatching {
            val image = when (src) {
                // GIF 的帧延迟修补已经交给 Coil 的 AnimatedImageDecoder（默认开启），
                // 不再需要在 Android 12/13 上手工 mmap + 改字节
                is UniFileSource -> decodeCoil(src.source.uri)
                is ByteBufferSource -> decodeCoil(src.source)
            }
            when (image) {
                is DrawableImage -> image.drawable.apply {
                    setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                }
                is BitmapImage -> src.close()
            }
            Image(image, src)
        }.onFailure {
            src.close()
            Log.e("Image", "Error", it)
        }.getOrNull()

        @JvmStatic
        fun create(bitmap: Bitmap): Image = Image(bitmap.asImage(), null)
    }
}

sealed interface ImageSource : AutoCloseable

interface UniFileSource : ImageSource {
    val source: UniFile
}

interface ByteBufferSource : ImageSource {
    val source: ByteBuffer
}
