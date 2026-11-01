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

import com.hippo.ehviewer.Settings

/**
 * `Settings` ↔ [ReaderSettings] 的互转。
 *
 * `GalleryView.LAYOUT_*` / `SCALE_*` 这套旧常量只活在旧栈里，到 [ReaderSettings] 字段的
 * 映射全部堆在这一层，`ReaderContract` 因此不碰 Android、可以直接单测。
 */
fun readerSettings(): ReaderSettings = ReaderSettings(
    vertical = Settings.readingDirection == LAYOUT_TOP_TO_BOTTOM,
    reverseLayout = Settings.readingDirection == LAYOUT_RIGHT_TO_LEFT,
    scaleMode = Settings.pageScaling,
    startPosition = Settings.startPosition,
    showPageInterval = Settings.showPageInterval,
    screenRotation = Settings.screenRotation,
    fullscreen = Settings.readingFullscreen,
    keepScreenOn = Settings.keepScreenOn,
    // 自动翻页开关每次进阅读器都要手动开（旧实现同款），只有间隔是持久化的
    autoPageInterval = 0,
    volumePage = Settings.volumePage,
    volumePageInterval = Settings.volumePageInterval,
    reverseVolumePage = Settings.reverseVolumePage,
    readTheme = Settings.readTheme,
    customScreenLightness = Settings.customScreenLightness,
    screenLightness = Settings.screenLightness,
)

/** 面板改完就落盘；同一批设置一次写完，与旧菜单对话框的行为一致 */
fun ReaderSettings.persist() {
    Settings.putReadingDirection(
        if (vertical) {
            LAYOUT_TOP_TO_BOTTOM
        } else if (reverseLayout) {
            LAYOUT_RIGHT_TO_LEFT
        } else {
            LAYOUT_LEFT_TO_RIGHT
        },
    )
    Settings.putPageScaling(scaleMode)
    Settings.putStartPosition(startPosition)
    Settings.putShowPageInterval(showPageInterval)
    Settings.putScreenRotation(screenRotation)
    Settings.putReadingFullscreen(fullscreen)
    Settings.putKeepScreenOn(keepScreenOn)
    // 关着的时候别把 0 写回去，否则上次用的间隔就没了
    if (autoPageInterval > 0) Settings.putTurnPageInterval(autoPageInterval)
    Settings.putVolumePage(volumePage)
    // 旧设置允许 0，但 0 页一次等于按键失灵，落盘前兜一下
    Settings.putVolumePageInterval(volumePageInterval.coerceAtLeast(1))
    Settings.putReverseVolumePage(reverseVolumePage)
    Settings.putReadTheme(readTheme)
    Settings.putCustomScreenLightness(customScreenLightness)
    Settings.putScreenLightness(screenLightness)
}
