package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FixedScale
import eu.kanade.tachiyomi.ui.reader.setting.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegions
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.getAction
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNavigationTest {
    @Test
    fun `fromPreference 映射六种点击区方案`() {
        assertEquals(LNavigation, ViewerNavigation.fromPreference(1, isVertical = false))
        assertEquals(KindlishNavigation, ViewerNavigation.fromPreference(2, isVertical = false))
        assertEquals(EdgeNavigation, ViewerNavigation.fromPreference(3, isVertical = false))
        assertEquals(RightAndLeftNavigation, ViewerNavigation.fromPreference(4, isVertical = false))
        assertEquals(DisabledNavigation, ViewerNavigation.fromPreference(5, isVertical = false))
        // 0 / 越界：竖排用 L，横排用左右
        assertEquals(LNavigation, ViewerNavigation.fromPreference(0, isVertical = true))
        assertEquals(RightAndLeftNavigation, ViewerNavigation.fromPreference(0, isVertical = false))
        assertEquals(LNavigation, ViewerNavigation.fromPreference(99, isVertical = true))
    }

    @Test
    fun `L 形分区：上三分之一上一页，下三分之一下一页`() {
        val regions = LNavigation.regions()
        assertEquals(NavigationRegion.PREV, regions.getAction(Offset(0.5f, 0.1f)))
        assertEquals(NavigationRegion.NEXT, regions.getAction(Offset(0.5f, 0.9f)))
        assertEquals(NavigationRegion.PREV, regions.getAction(Offset(0.1f, 0.5f)))
        assertEquals(NavigationRegion.NEXT, regions.getAction(Offset(0.9f, 0.5f)))
        // 正中留白，交给菜单
        assertEquals(NavigationRegion.MENU, regions.getAction(Offset(0.5f, 0.5f)))
    }

    @Test
    fun `左右分区按横排习惯：左区上一页 右区下一页`() {
        val regions = RightAndLeftNavigation.regions()
        assertEquals(NavigationRegion.LEFT, regions.getAction(Offset(0.1f, 0.5f)))
        assertEquals(NavigationRegion.RIGHT, regions.getAction(Offset(0.9f, 0.5f)))
        assertEquals(NavigationRegion.MENU, regions.getAction(Offset(0.5f, 0.5f)))
    }

    @Test
    fun `水平反转把左右分区镜像到另一侧`() {
        val original = RightAndLeftNavigation.regions()
        val inverted = RightAndLeftNavigation.regions(TappingInvertMode.HORIZONTAL)
        // 左区原本 0..0.33，反转后跑到 0.67..1
        assertEquals(1f, inverted.first().rect.right, 0.001f)
        assertEquals(0.67f, inverted.first().rect.left, 0.001f)
        // 同一坐标上的动作换了边：左边现在是「上一页」的反向 = 下一页
        assertEquals(NavigationRegion.LEFT, original.getAction(Offset(0.1f, 0.5f)))
        assertEquals(NavigationRegion.RIGHT, inverted.getAction(Offset(0.1f, 0.5f)))
        assertEquals(NavigationRegion.LEFT, inverted.getAction(Offset(0.9f, 0.5f)))
    }

    @Test
    fun `垂直反转把上下分区镜像`() {
        val inverted = LNavigation.regions(TappingInvertMode.VERTICAL)
        // 上区的 PREV 翻转成底部
        assertEquals(NavigationRegion.PREV, inverted.getAction(Offset(0.5f, 0.9f)))
        assertEquals(NavigationRegion.NEXT, inverted.getAction(Offset(0.5f, 0.1f)))
    }

    @Test
    fun `双向反转等价于上下加左右都镜像`() {
        val regions = RightAndLeftNavigation.regions(TappingInvertMode.BOTH)
        assertEquals(1f, regions.first().rect.right, 0.001f)
        assertEquals(NavigationRegion.LEFT, regions.getAction(Offset(0.9f, 0.5f)))
    }

    @Test
    fun `关闭点击导航时任何位置都是菜单`() {
        val regions: NavigationRegions = DisabledNavigation.regions()
        assertTrue(regions.isEmpty())
        assertEquals(NavigationRegion.MENU, regions.getAction(Offset(0.5f, 0.5f)))
    }

    @Test
    fun `缩放模式映射到 ContentScale`() {
        val src = Size(100f, 200f)
        val dst = Size(300f, 400f)
        assertEquals(ContentScale.Fit, ContentScale.fromPreferences(1, src, dst))
        assertEquals(ContentScale.Crop, ContentScale.fromPreferences(2, src, dst))
        assertEquals(ContentScale.FillWidth, ContentScale.fromPreferences(3, src, dst))
        assertEquals(ContentScale.FillHeight, ContentScale.fromPreferences(4, src, dst))
        assertTrue(ContentScale.fromPreferences(5, src, dst) is FixedScale)
        // 6 = 自适应：横图铺高、竖图铺宽
        assertEquals(ContentScale.FillWidth, ContentScale.fromPreferences(6, src, dst))
        assertEquals(ContentScale.FillHeight, ContentScale.fromPreferences(6, Size(400f, 200f), dst))
        // 越界回落 Fit
        assertEquals(ContentScale.Fit, ContentScale.fromPreferences(0, src, dst))
        assertEquals(ContentScale.Fit, ContentScale.fromPreferences(99, src, dst))
    }

    @Test
    fun `起始位置映射`() {
        assertEquals(AbsoluteAlignment.Left, Alignment.fromPreferences(1, isRtl = false, isVertical = false))
        assertEquals(AbsoluteAlignment.Right, Alignment.fromPreferences(1, isRtl = true, isVertical = false))
        assertEquals(Alignment.CenterHorizontally, Alignment.fromPreferences(1, isRtl = true, isVertical = true))
        assertEquals(AbsoluteAlignment.Left, Alignment.fromPreferences(2, isRtl = true, isVertical = false))
        assertEquals(AbsoluteAlignment.Right, Alignment.fromPreferences(3, isRtl = false, isVertical = false))
        assertEquals(Alignment.CenterHorizontally, Alignment.fromPreferences(0, isRtl = false, isVertical = false))
    }
}
