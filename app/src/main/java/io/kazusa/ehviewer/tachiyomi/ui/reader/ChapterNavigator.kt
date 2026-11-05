package io.kazusa.ehviewer.tachiyomi.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 底栏上的页码滑块：RTL 模式把整条反向，手指方向和翻页方向才对得上。
 *
 * 文字与滑块轨道都显式取 `onSurface` 一族的颜色：这里挂的是阅读器自己的深色主题，
 * 而 `LocalContentColor` 在阅读器里没有可靠来源（默认是黑色），靠继承会得到黑字配黑底。
 */
@Composable
fun ChapterNavigator(
    isRtl: Boolean,
    currentPage: Int,
    totalPages: Int,
    onSliderValueChange: (Int) -> Unit,
    containerColor: Color,
) = CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "$currentPage", color = contentColor)
            Slider(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                value = currentPage.toFloat(),
                valueRange = 1f..totalPages.toFloat(),
                steps = (totalPages - 2).coerceAtLeast(0),
                onValueChange = { onSliderValueChange(it.roundToInt().coerceIn(1, totalPages)) },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = contentColor.copy(alpha = 0.24f),
                ),
            )
            Text(text = "$totalPages", color = contentColor)
        }
    }
}
