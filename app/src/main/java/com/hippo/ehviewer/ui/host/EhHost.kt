/*
 * Copyright 2016 Hippo Seven
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
package com.hippo.ehviewer.ui.host

import androidx.annotation.IdRes
import androidx.annotation.StringRes

/**
 * 提示时长标记，沿用 `BaseScene` 时代的取值（`0` 短 / `1` 长）。
 *
 * 宿主据此在 Snackbar 与 Toast 的长短之间二选一；`SceneFragment` 已经删掉，
 * 这两个常量搬到这里，避免调用方再去碰旧场景层。
 */
const val TIP_LENGTH_SHORT = 0
const val TIP_LENGTH_LONG = 1

/**
 * 宿主壳（应用级 UI）能力。
 *
 * 页面需要「开抽屉 / 弹 Snackbar / 勾导航项 / 换头像」时只说这些动作，
 * 不再 `activity as MainActivity`。
 *
 * 抽屉只有左侧一个：右侧的「快速搜索 / 下载标签」抽屉是页面自己的
 * `ModalNavigationDrawer`，与宿主无关，所以这里不再带 `gravity` 参数。
 * `addAboveSnackView` / `removeAboveSnackView` 那套「把 FAB 顶到 Snackbar 之上」
 * 的机制随 XML 版 `CoordinatorLayout` 一起删除——Compose 页面各自用 `Scaffold` 管浮层。
 */
interface EhHost {
    /** 左侧抽屉是否可用（对应旧 `setDrawerLockMode(LOCK_MODE_UNLOCKED/LOCKED_CLOSED)`） */
    fun setLeftDrawerEnabled(enabled: Boolean)

    fun openLeftDrawer()

    fun closeLeftDrawer()

    fun toggleLeftDrawer()

    fun showTip(message: CharSequence, length: Int)

    fun showTip(@StringRes id: Int, length: Int)

    /** 投递一个宿主级对话框（工具层拿不到 Compose 上下文时用），见 [DialogRequest] */
    fun showDialog(request: DialogRequest)

    /** @param resId 0 for clear */
    fun setNavCheckedItem(@IdRes resId: Int)

    fun updateProfile()
}
