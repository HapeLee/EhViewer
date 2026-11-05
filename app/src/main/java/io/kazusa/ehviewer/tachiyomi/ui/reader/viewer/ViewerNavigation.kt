package io.kazusa.ehviewer.tachiyomi.ui.reader.viewer

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.tachiyomi.ui.reader.setting.TappingInvertMode
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.navigation.LNavigation
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import io.kazusa.ehviewer.tachiyomi.util.lang.invert

/**
 * 点击区分区模型（Tachiyomi 那套）。坐标一律归一化到 0..1，与具体屏幕尺寸无关。
 */
abstract class ViewerNavigation {
    // 浮层配色不放在 XML 里：从主题色派生，深浅主题自动跟着走
    sealed class NavigationRegion(@StringRes val nameRes: Int) {
        data object MENU : NavigationRegion(R.string.action_menu)
        data object PREV : NavigationRegion(R.string.nav_zone_prev)
        data object NEXT : NavigationRegion(R.string.nav_zone_next)
        data object LEFT : NavigationRegion(R.string.nav_zone_left)
        data object RIGHT : NavigationRegion(R.string.nav_zone_right)
    }

    data class Region(val rect: Rect, val type: NavigationRegion) {
        fun invert(invertMode: TappingInvertMode): Region {
            if (invertMode == TappingInvertMode.NONE) return this
            return copy(rect = rect.invert(invertMode))
        }
    }

    protected abstract val originalRegions: List<Region>

    fun regions(invertMode: TappingInvertMode = TappingInvertMode.NONE) = originalRegions.map { it.invert(invertMode) }

    companion object {
        fun fromPreference(value: Int, isVertical: Boolean) = when (value) {
            1 -> LNavigation
            2 -> KindlishNavigation
            3 -> EdgeNavigation
            4 -> RightAndLeftNavigation
            5 -> DisabledNavigation
            else -> if (isVertical) LNavigation else RightAndLeftNavigation
        }
    }
}

typealias NavigationRegions = List<ViewerNavigation.Region>

fun NavigationRegions.getAction(offset: Offset) = find { offset in it.rect }?.type ?: ViewerNavigation.NavigationRegion.MENU
