/*
 * Copyright 2022 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with EhViewer. If not, see <https://www.gnu.org/licenses/>.
 */
package io.kazusa.ehviewer.image

import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.kazusa.ehviewer.EhApplication.Companion.application
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 解码后的单页。
 *
 * 上游（EHz）这里还挂着二维码检测、自动裁边和 GIF 的 mmap 兼容路径，那些都靠 Rust/JNI；
 * 这一版只保留「解码 + 引用计数 + 可回收」这层骨架，解码交给 Coil，动图由 coil-gif 负责。
 * [refcnt] 与 [PageLoader][io.kazusa.ehviewer.gallery.PageLoader] 的缓存淘汰联动：
 * 缓存里有一份引用，界面再 pin 一份，两边都放开才真正 recycle。
 */
class Image private constructor(image: CoilImage, private val src: ImageSource?) {
    val refcnt = AtomicInteger(1)

    fun pin(): Boolean {
        while (true) {
            val current = refcnt.get()
            if (current == 0) return false
            if (refcnt.compareAndSet(current, current + 1)) return true
        }
    }

    /** 返回 true 表示引用清零、图像已经回收，调用方可以据此把这一页退回 Queued */
    fun unpin(): Boolean {
        val left = refcnt.decrementAndGet()
        if (left > 0) return false
        recycle()
        return true
    }

    val intrinsicSize = IntSize(image.width, image.height)

    val allocationSize: Int = image.size.toInt()

    /** 解码后端交给 Coil，这里保留字段是为了让 [recycle] 能区分位图和 Drawable */
    var innerImage: CoilImage? = image

    private fun recycle() {
        when (val image = innerImage) {
            is DrawableImage -> src?.close()
            // Compose/RenderThread may still reference a bitmap after the UI unpins it.
            // Let those references and the GC determine when its pixel memory is freed.
            is BitmapImage -> Unit
            else -> {}
        }
        innerImage = null
    }

    companion object {
        private fun targetSize(): Int = with(application.resources.displayMetrics) {
            minOf(widthPixels, heightPixels) * 4 / 3
        }

        suspend fun decode(src: ImageSource): Image {
            val data: Any = when (src) {
                is PathSource -> src.source
                is ByteBufferSource -> src.source
            }
            val request = ImageRequest.Builder(application)
                .data(data)
                .size(Size(targetSize(), targetSize()))
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .maxBitmapSize(Size.ORIGINAL)
                .allowHardware(false)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            val result = SingletonImageLoader.get(application).execute(request)
            val image = when (result) {
                is SuccessResult -> result.image
                is ErrorResult -> throw result.throwable
                else -> error("Unexpected coil result: $result")
            }
            return Image(image, src).apply {
                // 位图在内存里，源文件可以立刻放手；Drawable（动图）还要靠源继续读
                if (image is BitmapImage) src.close()
            }
        }
    }
}

sealed interface ImageSource : AutoCloseable

class PathSource(val source: File) : ImageSource {
    override fun close() = Unit
}

class ByteBufferSource(
    val source: ByteBuffer,
    private val release: () -> Unit = {},
) : ImageSource {
    override fun close() = release()
}
