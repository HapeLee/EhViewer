/*
 * Copyright 2026 EhViewer contributors
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
package com.hippo.image

import android.graphics.Bitmap
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MAX_TILE_PIXELS = 512 * 512

/**
 * 把 Bitmap 的一块区域上传成 GL 纹理。
 *
 * 对应原 `image.c` 的 `nativeTexImage`：
 * - 只处理不超过 512×512 的块
 * - RGBA_8888 走 GL_RGBA8/UNSIGNED_BYTE，RGBA_F16 走 GL_RGBA16F/HALF_FLOAT
 * - `init = true` 时 `glTexImage2D`，否则 `glTexSubImage2D` 到 (0, 0)
 *
 * 注意：这是给旧 GL 阅读器（[com.hippo.ehviewer.ui.GalleryActivity]）用的过渡实现，
 * 阅读器换成 Compose + telephoto 之后整段可以删除。
 */
internal fun uploadTileTexture(
    bitmap: Bitmap,
    init: Boolean,
    offsetX: Int,
    offsetY: Int,
    width: Int,
    height: Int,
) {
    if (width <= 0 || height <= 0 || width * height > MAX_TILE_PIXELS) return
    if (bitmap.isRecycled) return

    val isF16 = bitmap.config == Bitmap.Config.RGBA_F16
    val bytesPerPixel = if (isF16) 8 else 4
    val buffer = ByteBuffer.allocateDirect(width * height * bytesPerPixel).order(ByteOrder.nativeOrder())

    val sx = offsetX.coerceAtLeast(0)
    val sy = offsetY.coerceAtLeast(0)
    val copyWidth = minOf(width, bitmap.width - sx)
    val copyHeight = minOf(height, bitmap.height - sy)
    if (copyWidth <= 0 || copyHeight <= 0) return

    if (copyWidth == width && copyHeight == height) {
        // 常规路径：整块都在图内，交给 native 拷贝像素，行宽天然对齐
        val region = Bitmap.createBitmap(bitmap, sx, sy, copyWidth, copyHeight)
        try {
            region.copyPixelsToBuffer(buffer)
        } finally {
            if (region !== bitmap) region.recycle()
        }
    } else {
        // 边缘不足一块：逐像素补零，避免行宽与 GL 期望不一致
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, sx, sy, copyWidth, copyHeight)
        for (pixel in pixels) {
            buffer.put((pixel and 0xFF).toByte())
            buffer.put((pixel shr 8 and 0xFF).toByte())
            buffer.put((pixel shr 16 and 0xFF).toByte())
            buffer.put((pixel shr 24 and 0xFF).toByte())
        }
    }
    buffer.position(0)

    val type = if (isF16) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
    val internalFormat = if (isF16) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
    if (init) {
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            type,
            buffer,
        )
    } else {
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            type,
            buffer,
        )
    }
}
