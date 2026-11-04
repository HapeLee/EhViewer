package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 阅读器最上层的两块遮罩：亮度压暗（只在负值时有）与彩色滤镜。
 *
 * 用 `Canvas` 而不是 `Box().background()`：滤镜要走 [BlendMode]，background 没有混合模式。
 */
@Composable
fun ReaderContentOverlay(
    brightness: (() -> Int)?,
    color: (() -> Int)?,
    colorBlendMode: BlendMode = BlendMode.SrcOver,
) {
    if (brightness != null) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alpha = abs(brightness()) / 100f
            drawRect(Color.Black, alpha = alpha)
        }
    }

    if (color != null) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = Color(color()),
                blendMode = colorBlendMode,
            )
        }
    }
}
