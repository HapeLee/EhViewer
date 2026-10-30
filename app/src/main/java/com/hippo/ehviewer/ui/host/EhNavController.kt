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

import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.ui.compose.nav.EhNavigator
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.navSlot

/**
 * 全应用唯一的返回栈。
 *
 * 状态就一份：一份 route 列表。渲染层（现在是用兼容承载画出来的旧 Scene，
 * 以后是页面自己的 @Composable）只负责把 [currentRoute] 画出来，
 * 所以「返回键该走哪一层」只有一处判定（[com.hippo.ehviewer.ui.compose.nav.ehBackAction]）。
 *
 * 组合还没起来时（Activity.onCreate 里解析启动 Intent）压栈会先攒在 [pending]，
 * attach 之后按序回放，避免冷启动跳转被吃掉。
 */
class EhNavController internal constructor(
    internal val entries: MutableList<NavKey>,
    /** 弹到栈底还想再弹 = 收掉整个宿主（与旧 `StageActivity.finishScene` 一致） */
    private val onFinishStage: () -> Unit = {},
) : EhNavigator {
    private var attached: MutableList<EhRoute>? = null
    private val pending = ArrayDeque<(MutableList<EhRoute>) -> Unit>()
    private var replaceRootOnFlush = false

    val size: Int
        get() = (attached ?: entries).size

    val currentRoute: EhRoute?
        get() = (attached ?: entries).lastOrNull() as? EhRoute

    fun indexOf(route: NavKey): Int = (attached ?: entries).indexOf(route)

    /** 宿主首帧的组合把 `rememberNavBackStack` 出来的栈挂上来 */
    fun attach(stack: MutableList<EhRoute>) {
        attached = stack
        if (replaceRootOnFlush) {
            replaceRootOnFlush = false
            stack.clear()
        }
        while (pending.isNotEmpty()) {
            pending.removeFirst()(stack)
        }
    }

    fun detach() {
        attached = null
    }

    /** 冷启动跳转：覆盖掉占位用的起始页，而不是叠在它上面 */
    fun pushAsRoot(route: EhRoute) {
        if (attached == null && pending.isEmpty()) {
            replaceRootOnFlush = true
        } else {
            clearAboveRoot()
        }
        push(route)
    }

    /** 只留栈底页面（导航菜单切页用） */
    fun clearAboveRoot() {
        apply { stack ->
            while (stack.size > 1) {
                stack.removeAt(stack.lastIndex)
            }
        }
    }

    /** 弹到指定 route（保留它）；不在栈里或它就是栈底时返回 false */
    fun popTo(route: NavKey): Boolean {
        val stack = attached ?: return false
        val index = stack.indexOf(route)
        if (index < 0) return false
        while (stack.size > index + 1) {
            stack.removeAt(stack.lastIndex)
        }
        return true
    }

    /** 弹出指定 route 及其之上的所有页面；栈底不允许弹出 */
    fun popRoute(route: NavKey): Boolean {
        val stack = attached ?: return false
        val index = stack.indexOf(route)
        if (index <= 0) return false
        while (stack.size >= index) {
            stack.removeAt(stack.lastIndex)
        }
        return true
    }

    /** 重新创建栈顶页面（设置改完刷新用） */
    fun refreshTop() {
        apply { stack ->
            val route = stack.lastOrNull() ?: return@apply
            stack.removeAt(stack.lastIndex)
            stack.add(route)
        }
    }

    override fun push(route: EhRoute) {
        apply { stack ->
            val existing = stack.indexOfFirst { it.navSlot == route.navSlot }
            if (existing >= 0) {
                // 同一个页面已在栈里（SINGLE_TASK）：上面的全弹掉，不重复压栈
                while (stack.size > existing + 1) {
                    stack.removeAt(stack.lastIndex)
                }
                // 参数变了（比如下载页换了 gid）就把这一跳的内容换掉
                if (stack[existing] != route) {
                    stack[existing] = route
                }
            } else {
                stack.add(route)
            }
        }
    }

    override fun pop() {
        apply { stack ->
            if (stack.size > 1) {
                stack.removeAt(stack.lastIndex)
            } else {
                onFinishStage()
            }
        }
    }

    override fun replaceTop(route: EhRoute) {
        apply { stack ->
            if (stack.isEmpty()) {
                stack.add(route)
            } else {
                stack[stack.lastIndex] = route
            }
        }
    }

    private fun apply(op: (MutableList<EhRoute>) -> Unit) {
        val stack = attached
        if (stack == null) {
            pending.addLast(op)
        } else {
            op(stack)
        }
    }
}
