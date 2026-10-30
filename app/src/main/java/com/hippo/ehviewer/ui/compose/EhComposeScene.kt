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
package com.hippo.ehviewer.ui.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hippo.ehviewer.ui.compose.nav.EhBackAction
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehBackAction
import com.hippo.ehviewer.ui.compose.nav.legacyRouteOf
import com.hippo.ehviewer.ui.compose.theme.EhTheme
import com.hippo.ehviewer.ui.scene.SolidScene

/**
 * 用 Compose + Navigation 3 承载内容的 Scene。
 *
 * 迁移期定位：仍然是一个普通的 [SolidScene]，所以 `Announcer`、`startSceneForCheckStep`
 * 等既有能力全部保留；区别只是它内部的内容由 [NavDisplay] 管理，而不是 XML 布局。
 *
 * P7-a 起起始路由不再由子类硬编码：统一从 `arguments` 经
 * [legacyRouteOf] 还原成 `EhRoute`。这样「同一个页面」在旧栈和新栈里
 * 是同一个 route 实例语义，P7-b 换宿主时不必再改 15 个页面。
 *
 * 子类只需要注册路由表：
 * ```kotlin
 * class XxxScene : EhComposeScene() {
 *     @Composable
 *     override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
 *         entry<EhRoute.Xxx> { XxxScreen(...) }
 *     }
 * }
 * ```
 */
abstract class EhComposeScene : SolidScene() {
    /**
     * 本 Scene 内部 back stack 的起始路由。
     *
     * `arguments` 在 `setArguments` 后就固定，所以这里缓存一次解析结果，
     * 避免每次重组都去 unparcel 一遍 `GalleryDetail`。
     */
    private val initialRoute: EhRoute by lazy {
        legacyRouteOf(javaClass.name, arguments)
            ?: error("Can't resolve route for ${javaClass.name}, args=$arguments")
    }

    /** 本 Scene 内部 back stack 的起始路由 */
    protected val startRoute: NavKey
        get() = initialRoute

    /**
     * 本 Scene 对应的 route。
     *
     * 页面参数（比如守卫链的 `target`）从这里取，不要再翻 `arguments`——
     * `arguments` 是旧协议，P7-c 之后就没有了。
     */
    protected val ehRoute: EhRoute
        get() = initialRoute

    /**
     * 注册路由表。需要压栈时直接操作 [backStack]（Nav3 的 back stack 就是可变列表）。
     */
    @Composable
    protected abstract fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>)

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        // View 被销毁时一并销毁组合，避免 Fragment 复用时泄漏
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            EhTheme {
                val backStack = rememberNavBackStack(startRoute)
                NavDisplay(
                    backStack = backStack,
                    onBack = {
                        when (ehBackAction(backStack.size)) {
                            EhBackAction.POP_ENTRY -> backStack.removeAt(backStack.lastIndex)
                            EhBackAction.POP_SCENE -> finish()
                        }
                    },
                    entryDecorators = listOf(
                        // 保证每个入口的 rememberSaveable 状态与 ViewModel 各自独立
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider { registerDestinations(backStack) },
                )
            }
        }
    }
}
