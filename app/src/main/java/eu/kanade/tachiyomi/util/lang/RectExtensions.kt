package eu.kanade.tachiyomi.util.lang

import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.setting.TappingInvertMode

/** 点击区反转：水平方向左右镜像、垂直方向上下镜像 */
fun Rect.invert(invertMode: TappingInvertMode): Rect = copy(
    left = if (invertMode.shouldInvertHorizontal) 1f - right else left,
    right = if (invertMode.shouldInvertHorizontal) 1f - left else right,
    top = if (invertMode.shouldInvertVertical) 1f - bottom else top,
    bottom = if (invertMode.shouldInvertVertical) 1f - top else bottom,
)
