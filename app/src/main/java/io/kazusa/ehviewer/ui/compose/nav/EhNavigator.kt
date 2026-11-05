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
package io.kazusa.ehviewer.ui.compose.nav

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.fragment.app.Fragment

/**
 * 全应用唯一的导航入口。
 *
 * P7-b 之后实现只有一份：[io.kazusa.ehviewer.ui.host.EhNavController]，
 * 它直接操作那条唯一的 route 列表，不再绕 `Announcer` / 反射。
 *
 * 只保留旧栈真能表达的三个动作：[push]、[pop]、[replaceTop]。
 */
interface EhNavigator {
    /** 压入一个页面 */
    fun push(route: EhRoute)

    /** 弹出当前页面（等价于 `finish()`） */
    fun pop()

    /** 用新页面替换当前页面（压新的、摘旧的），用于「进度页 → 详情页」这类中转页 */
    fun replaceTop(route: EhRoute)

    /**
     * 当前页面是不是栈底。
     *
     * 只有栈底页面需要「再按一次退出」这类宿主级行为；其余页面的返回键交给
     * `NavDisplay` 直接弹栈。旧实现看的是 `SceneFragment.stackIndex`。
     */
    val isRoot: Boolean
}

/** 能提供返回栈的宿主；[EhNavHostActivity] 实现它 */
interface EhNavigatorProvider {
    val navigator: EhNavigator
}

/**
 * 返回栈在组合树里的入口。
 *
 * 页面出壳（P7-c）之后不再有 Fragment 接收者，压栈 / 弹栈从这里取。
 * 宿主一定会提供；脱离宿主运行（预览 / 单测）时降级成空实现，安静失效。
 */
val LocalEhNavigator = staticCompositionLocalOf<EhNavigator> { NoOpNavigator }

/** 从组合里取返回栈 */
@Composable
fun ehNavigator(): EhNavigator = LocalEhNavigator.current

/** 从任意 Fragment 取返回栈 */
fun Fragment.ehNavigator(): EhNavigator = activity?.ehNavigator() ?: NoOpNavigator

/** 从宿主 Activity 取返回栈；Intent / 剪贴板 / 导航菜单这类入口用 */
fun Activity.ehNavigator(): EhNavigator = (this as? EhNavigatorProvider)?.navigator ?: NoOpNavigator

private object NoOpNavigator : EhNavigator {
    override fun push(route: EhRoute) = Unit
    override fun pop() = Unit
    override fun replaceTop(route: EhRoute) = Unit
    override val isRoot = true
}
