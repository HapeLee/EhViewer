package com.hippo.ehviewer.ui.reader

import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize

/**
 * 最小的 Drawable 画笔：动图（GIF / 动态 WebP）由 Drawable 自己推进帧，
 * 这里只负责把它的 bounds 对齐到绘制区域。EHz 用的是 accompanist 的实现，
 * 为了不多拉一个依赖，这里自己写一份。
 */
class DrawablePainter(val drawable: Drawable) : Painter() {

    override val intrinsicSize: Size = IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight).toSize()

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
