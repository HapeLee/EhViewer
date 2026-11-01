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

/**
 * 阅读方向与缩放模式的取值。
 *
 * 原来定义在 `com.hippo.glgallery.GalleryView`（旧 GL 阅读器），删旧栈时搬到这里——
 * 值必须保持原样，因为 `Settings` 里存的就是这些整数。
 */
const val LAYOUT_LEFT_TO_RIGHT = 0
const val LAYOUT_RIGHT_TO_LEFT = 1
const val LAYOUT_TOP_TO_BOTTOM = 2

const val SCALE_ORIGIN = 0
const val SCALE_FIT_WIDTH = 1
const val SCALE_FIT_HEIGHT = 2
const val SCALE_FIT = 3
const val SCALE_FIXED = 4

/** 图片小于视口时的锚点；值必须与旧 `ImageView.START_POSITION_*` 一致 */
const val START_POSITION_TOP_LEFT = 0
const val START_POSITION_TOP_RIGHT = 1
const val START_POSITION_BOTTOM_LEFT = 2
const val START_POSITION_BOTTOM_RIGHT = 3
const val START_POSITION_CENTER = 4

/** 阅读主题：0 跟随 App，1 深色，2 浅色。与旧 `GalleryActivity.attachBaseContext` 的三分支同序 */
const val READ_THEME_FOLLOW_APP = 0
const val READ_THEME_DARK = 1
const val READ_THEME_LIGHT = 2

/**
 * 缩放模式 → `ContentScale`。
 *
 * [SCALE_FIXED] 在旧实现里走 `ImageView` 的 `default` 分支，倍率 `mScaleValue` 恒定 1.0f
 * （`GalleryView` 从来不设它），所以它与 [SCALE_ORIGIN] 是同一个行为：按原图尺寸绘制。
 * 这里照实映射成 `ContentScale.None`，不再是「设置保留但不生效」。
 */
internal fun contentScaleOf(scaleMode: Int): ContentScale = when (scaleMode) {
    SCALE_FIT_WIDTH -> ContentScale.FillWidth
    SCALE_FIT_HEIGHT -> ContentScale.FillHeight
    SCALE_ORIGIN, SCALE_FIXED -> ContentScale.None
    else -> ContentScale.Fit
}

/**
 * 起始位置 → `Alignment`，交给 `ZoomableImage` 的 `alignment`：
 * 只有图片比视口小（即 `SCALE_ORIGIN` / `SCALE_FIXED`）时才看得出来。
 */
internal fun alignmentOf(startPosition: Int): Alignment = when (startPosition) {
    START_POSITION_TOP_LEFT -> Alignment.TopStart
    START_POSITION_BOTTOM_LEFT -> Alignment.BottomStart
    START_POSITION_BOTTOM_RIGHT -> Alignment.BottomEnd
    START_POSITION_CENTER -> Alignment.Center
    // START_POSITION_TOP_RIGHT 与越界值：旧 `ImageView` 的默认分支也是右上
    else -> Alignment.TopEnd
}

/**
 * 点击区去向，对应旧 `GalleryView` 的 `LEFT_AREA` 三分区。
 *
 * 引导文案（`guide_gallery_left/right/menu/progress`）承诺的就是这四件事，
 * 少一件用户就会觉得「按了没反应」。
 */
internal enum class ReaderTapZone {
    Previous,
    Next,
    Menu,
    Progress,
}

/**
 * 点击落在哪个区。
 *
 * 沿翻页轴分三段：前 1/3 上一页、后 1/3 下一页；中间 1/3 再按另一根轴分成菜单与进度
 * ——横向是上下分（上半菜单、下半进度，与引导图一致），纵向是左右分。
 *
 * 横向阅读方向为从右往左时，左右两段对调——用户眼里的「前一页」在右边。
 */
internal fun tapZoneOf(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    vertical: Boolean,
    reverse: Boolean,
): ReaderTapZone {
    if (width <= 0 || height <= 0) return ReaderTapZone.Menu
    val third = 1f / 3f
    return if (vertical) {
        val at = y / height
        when {
            at < third -> ReaderTapZone.Previous
            at > 1f - third -> ReaderTapZone.Next
            x / width < 0.5f -> ReaderTapZone.Menu
            else -> ReaderTapZone.Progress
        }
    } else {
        val at = x / width
        when {
            at < third -> if (reverse) ReaderTapZone.Next else ReaderTapZone.Previous
            at > 1f - third -> if (reverse) ReaderTapZone.Previous else ReaderTapZone.Next
            y / height < 0.5f -> ReaderTapZone.Menu
            else -> ReaderTapZone.Progress
        }
    }
}
