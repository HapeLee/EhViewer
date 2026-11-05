package io.kazusa.ehviewer.tachiyomi.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 常驻页码。
 *
 * 颜色按底色明暗翻转：白底用深字 + 浅描边，黑/灰底用浅字 + 深描边。
 * 阅读器已经按自己的主题套了 `EhTheme`，这里直接读 [MaterialTheme] 的 background 就够了。
 */
@Composable
fun PageIndicatorText(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    if (currentPage <= 0 || totalPages <= 0) return
    val onLightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val textColor = if (onLightBackground) Color(45, 45, 45) else Color(235, 235, 235)
    val shadowColor = if (onLightBackground) Color(235, 235, 235) else Color(45, 45, 45)
    val style = TextStyle(
        color = textColor,
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        shadow = Shadow(color = shadowColor, blurRadius = 4f),
    )
    Box(modifier = modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        Text(text = "$currentPage / $totalPages", style = style)
    }
}
