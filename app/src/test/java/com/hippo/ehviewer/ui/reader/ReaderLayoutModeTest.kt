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
package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 旧 `GalleryView` 的缩放 / 起始位置常量到 Compose 的映射。
 *
 * 这两张表是「设置里存的是整数、UI 只认对象」的唯一接缝，映射错了不会有编译错误，
 * 只会在真机上以「某一项设置点了没反应」的形式出现。
 */
class ReaderLayoutModeTest {
    @Test
    fun `scale modes map onto content scales`() {
        assertEquals(ContentScale.None, contentScaleOf(SCALE_ORIGIN))
        assertEquals(ContentScale.FillWidth, contentScaleOf(SCALE_FIT_WIDTH))
        assertEquals(ContentScale.FillHeight, contentScaleOf(SCALE_FIT_HEIGHT))
        assertEquals(ContentScale.Fit, contentScaleOf(SCALE_FIT))
        // 旧实现的 fixed scale 倍率恒为 1，行为等同 actual size，不是「不支持」
        assertEquals(ContentScale.None, contentScaleOf(SCALE_FIXED))
    }

    @Test
    fun `unknown scale mode falls back to fit`() {
        assertEquals(ContentScale.Fit, contentScaleOf(-1))
        assertEquals(ContentScale.Fit, contentScaleOf(99))
    }

    @Test
    fun `start positions map onto alignments`() {
        assertEquals(Alignment.TopStart, alignmentOf(START_POSITION_TOP_LEFT))
        assertEquals(Alignment.TopEnd, alignmentOf(START_POSITION_TOP_RIGHT))
        assertEquals(Alignment.BottomStart, alignmentOf(START_POSITION_BOTTOM_LEFT))
        assertEquals(Alignment.BottomEnd, alignmentOf(START_POSITION_BOTTOM_RIGHT))
        assertEquals(Alignment.Center, alignmentOf(START_POSITION_CENTER))
    }

    @Test
    fun `unknown start position falls back to top right`() {
        // 旧 ImageView 的 default 分支就是右上
        assertEquals(Alignment.TopEnd, alignmentOf(-1))
        assertEquals(Alignment.TopEnd, alignmentOf(99))
    }

    @Test
    fun `horizontal tap zones split the width into thirds`() {
        // 900x1800：左 1/3 上一页、右 1/3 下一页，中间再按上下分菜单与进度
        assertEquals(ReaderTapZone.Previous, tapZoneOf(100f, 900f, 900, 1800, vertical = false, reverse = false))
        assertEquals(ReaderTapZone.Next, tapZoneOf(800f, 900f, 900, 1800, vertical = false, reverse = false))
        assertEquals(ReaderTapZone.Menu, tapZoneOf(450f, 400f, 900, 1800, vertical = false, reverse = false))
        assertEquals(ReaderTapZone.Progress, tapZoneOf(450f, 1400f, 900, 1800, vertical = false, reverse = false))
    }

    @Test
    fun `horizontal tap zones follow the reading direction`() {
        // 从右往左：前一页在右边
        assertEquals(ReaderTapZone.Next, tapZoneOf(100f, 900f, 900, 1800, vertical = false, reverse = true))
        assertEquals(ReaderTapZone.Previous, tapZoneOf(800f, 900f, 900, 1800, vertical = false, reverse = true))
        // 中间的分区不受方向影响
        assertEquals(ReaderTapZone.Menu, tapZoneOf(450f, 400f, 900, 1800, vertical = false, reverse = true))
    }

    @Test
    fun `vertical tap zones use the vertical axis`() {
        assertEquals(ReaderTapZone.Previous, tapZoneOf(450f, 100f, 900, 1800, vertical = true, reverse = false))
        assertEquals(ReaderTapZone.Next, tapZoneOf(450f, 1700f, 900, 1800, vertical = true, reverse = false))
        assertEquals(ReaderTapZone.Menu, tapZoneOf(100f, 900f, 900, 1800, vertical = true, reverse = false))
        assertEquals(ReaderTapZone.Progress, tapZoneOf(800f, 900f, 900, 1800, vertical = true, reverse = false))
    }

    @Test
    fun `zero sized viewport falls back to menu`() {
        assertEquals(ReaderTapZone.Menu, tapZoneOf(0f, 0f, 0, 0, vertical = false, reverse = false))
    }
}
