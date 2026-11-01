/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui.host

import android.view.View
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
 * 不再 `activity as MainActivity`。这样 P7-b 之后宿主无论是一个 `ComponentActivity`
 * 还是单个 `NavDisplay`，页面代码都不用改。
 *
 * 注意这里只放**壳**能力；页面自己的对话框、剪贴板、下载入口仍然用 Activity 上下文。
 */
interface EhHost {
    fun setDrawerLockMode(lockMode: Int, edgeGravity: Int)

    fun openDrawer(drawerGravity: Int)

    fun closeDrawer(drawerGravity: Int)

    fun toggleDrawer(drawerGravity: Int)

    fun showTip(message: CharSequence, length: Int)

    fun showTip(@StringRes id: Int, length: Int)

    /** @param resId 0 for clear */
    fun setNavCheckedItem(@IdRes resId: Int)

    fun updateProfile()

    fun addAboveSnackView(view: View)

    fun removeAboveSnackView(view: View)
}
