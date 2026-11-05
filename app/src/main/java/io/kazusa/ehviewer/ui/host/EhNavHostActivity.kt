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
package io.kazusa.ehviewer.ui.host

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.ui.EhActivity
import io.kazusa.ehviewer.ui.compose.nav.EhNavigator
import io.kazusa.ehviewer.ui.compose.nav.EhNavigatorProvider
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.LocalEhNavigator
import io.kazusa.ehviewer.ui.compose.nav.NavTransitions
import io.kazusa.ehviewer.ui.compose.nav.decodeRoute
import io.kazusa.ehviewer.ui.compose.nav.hasLeftDrawer
import io.kazusa.ehviewer.ui.compose.nav.initialCheckRoute
import io.kazusa.ehviewer.ui.compose.nav.legacyRouteOf
import io.kazusa.ehviewer.ui.compose.nav.navCheckedItem
import io.kazusa.ehviewer.ui.compose.reader.ReaderRoute
import io.kazusa.ehviewer.ui.compose.theme.EhTheme
import io.kazusa.ehviewer.ui.compose.theme.ehWindowBackground
import io.kazusa.ehviewer.ui.page.downloads.DownloadsScene
import io.kazusa.ehviewer.ui.page.favorites.FavoritesScene
import io.kazusa.ehviewer.ui.page.gallery.GalleryCommentsScene
import io.kazusa.ehviewer.ui.page.gallery.GalleryDetailScene
import io.kazusa.ehviewer.ui.page.gallery.GalleryInfoScene
import io.kazusa.ehviewer.ui.page.gallery.GalleryListScene
import io.kazusa.ehviewer.ui.page.gallery.GalleryPreviewsScene
import io.kazusa.ehviewer.ui.page.gallery.ProgressScene
import io.kazusa.ehviewer.ui.page.history.HistoryScene
import io.kazusa.ehviewer.ui.page.security.CookieSignInScene
import io.kazusa.ehviewer.ui.page.security.SecurityScene
import io.kazusa.ehviewer.ui.page.security.SelectSiteScene
import io.kazusa.ehviewer.ui.page.security.SignInScene
import io.kazusa.ehviewer.ui.page.security.WebViewSignInScene
import io.kazusa.ehviewer.ui.page.settings.AboutScene
import io.kazusa.ehviewer.ui.page.settings.SettingsCategoryScene
import io.kazusa.ehviewer.ui.page.settings.SettingsScene

/**
 * 宿主壳：接收「页面内容」这个 @Composable，返回包好的组合。
 *
 * `MainActivity` 用它把返回栈包进抽屉 / Snackbar 容器（见 `MainShell`）；
 * 子类不覆写时就是原样透传。
 */
typealias HostShell = @Composable (content: @Composable () -> Unit) -> Unit

/**
 * 应用级导航宿主：一个 Activity、一棵 Compose 树、一个 [NavDisplay] 返回栈。
 *
 * 旧实现是「Activity 里的 Fragment 场景栈」，页面内部再各自套一个 Nav3 栈，
 * 返回键要在两层之间判断。这里把两层合成一层：
 * 页面就是返回栈里的 entry，[EhNavController] 是唯一状态源。
 */
abstract class EhNavHostActivity : EhActivity(), EhNavigatorProvider {
    private val navEntries = mutableStateListOf<NavKey>()

    /** 唯一的返回栈；弹到栈底还继续弹就是收掉宿主 */
    val navController: EhNavController = EhNavController(
        entries = navEntries,
        onFinishStage = ::finish,
    )

    override val navigator: EhNavigator
        get() = navController

    /**
     * 宿主壳能力在组合里的实现；真正的实现者是实现了 [EhHost] 的子类（目前只有
     * `MainActivity`）。没实现时页面查不到壳能力，提示类调用安静失效。
     */
    internal val hostState: EhHostState = object : EhHostState {
        private val host: EhHost? get() = this@EhNavHostActivity as? EhHost
        override fun showTip(message: CharSequence, length: Int) {
            host?.showTip(message, length)
        }

        override fun showTip(id: Int, length: Int) {
            host?.showTip(id, length)
        }

        override fun showDialog(request: DialogRequest) {
            host?.showDialog(request)
        }

        override fun setNavCheckedItem(resId: Int) {
            host?.setNavCheckedItem(resId)
        }

        override fun setLeftDrawerEnabled(enabled: Boolean) {
            host?.setLeftDrawerEnabled(enabled)
        }

        override fun toggleLeftDrawer() {
            host?.toggleLeftDrawer()
        }

        override fun updateProfile() {
            host?.updateProfile()
        }

        override fun hideSoftInput() {
            SoftwareKeyboardControllerCompat(window.decorView).hide()
        }
    }

    /**
     * 冷启动的第一跳。
     *
     * 守卫链（手势锁 → 登录 → 选站点）由 [initialCheckRoute] 统一跑，
     * 所以子类这里只回答「没有守卫时停在哪」。
     */
    protected abstract fun initialRoute(): EhRoute

    /**
     * 宿主壳：把页面栈包进抽屉 / Snackbar 之类的应用级容器。
     *
     * 默认什么都不包（`{ it }`），`MainActivity` 覆写成 [MainShell]。返回值在 `onCreate`
     * 里取一次，组合树重组不会换掉外层容器。
     */
    protected open fun shell(): HostShell = { it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 兜底返回：优先级最低，页面内 BackHandler 与 NavDisplay 的返回回调都比它晚注册
        installBackFallback()
        // 窗口背景跟动态取色对齐：没给自己铺底的页面（下载页）会直接透出窗口色。
        // manifest 上挂的是库自带的 `Theme.Material3.DayNight.NoActionBar`，它的
        // windowBackground 是 M3 的静态色（不跟 Monet，也不是 AMOLED 的纯黑），
        // 所以只能在这里按当前色板重新铺一次（启动画面那一帧仍然是库的默认色）。
        window.setBackgroundDrawable(ColorDrawable(ehWindowBackground(this)))
        // 先把根页面放进栈（组合还没起来，压栈会攒在 EhNavController 里）
        navEntries.add(initialRoute())
        val shell = shell()
        setContent {
            EhTheme {
                shell {
                    CompositionLocalProvider(
                        LocalEhHost provides hostState,
                        LocalEhNavigator provides navController,
                    ) {
                        SceneStack()
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ACTION_START_SCENE != intent.action || !startSceneFromIntent(intent)) {
            onUnrecognizedIntent(intent)
        }
    }

    /**
     * @return `true` for start scene
     */
    private fun startSceneFromIntent(intent: Intent): Boolean {
        // 首选 route 载荷：页面出壳之后，「去哪个页面」就是 route 本身
        intent.getByteArrayExtra(KEY_SCENE_ROUTE)?.let { bytes ->
            val route = decodeRoute(bytes) ?: return false
            pushRoute(route)
            return true
        }
        // 兜底：服务进程给不了 route 对象，只能给「名字 + 几个标量」（目前只有下载页）
        val sceneName = intent.getStringExtra(KEY_SCENE_NAME) ?: return false
        val args = intent.getBundleExtra(KEY_SCENE_ARGS)
        val route = onStartSceneFromIntent(sceneName, args) ?: return false
        pushRoute(route)
        return true
    }

    private fun handleLaunchIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_MAIN -> pushRoute(initialRoute())
            ACTION_START_SCENE -> if (startSceneFromIntent(intent)) return
        }
        // Can't recognize intent
        onUnrecognizedIntent(intent)
    }

    /**
     * Start scene from `Intent`, it might be not safe,
     * Correct it here.
     *
     * @param sceneName 旧协议里的 Scene 类名（页面出壳后仍保留原名，见 `EhRoutes`)
     * @return `null` for do not start scene
     */
    protected open fun onStartSceneFromIntent(sceneName: String, args: Bundle?): EhRoute? = legacyRouteOf(sceneName, args)

    /**
     * Can't recognize intent in first time `onCreate` and `onNewIntent`,
     * null included.
     */
    protected open fun onUnrecognizedIntent(intent: Intent?) {}

    /** 每次压栈之后回调；旧实现用它延迟检查剪贴板 */
    protected open fun onTransactScene() {}

    /** 子类可以在这里拦下返回键（抽屉开着时先关抽屉） */
    protected open fun onInterceptBack(): Boolean = false

    /** 返回栈深度：栈空表示宿主还没压过任何页面（守卫链要先跑） */
    val sceneCount: Int
        get() = navController.size

    /** 压一个页面；栈空时先跑守卫链 */
    protected fun pushRoute(route: EhRoute) {
        navController.push(if (sceneCount == 0) initialCheckRoute(route) else route)
        onTransactScene()
    }

    /** 重建栈顶页面（设置改完刷新用） */
    protected fun refreshTopScene() {
        navController.refreshTop()
    }

    /**
     * 返回键的兜底处理。
     *
     * 开了 `enableOnBackInvokedCallback` 之后系统不再回调 `onBackPressed()`，一切走
     * `OnBackPressedDispatcher`；这里注册的 callback 是**最早**注册的一个，所以优先级最低：
     * 页面里的 `BackHandler`（关评论框、退搜索、关抽屉）和 `NavDisplay` 自己的返回回调
     * （栈深 > 1 时弹栈，带预测性返回动画）都在它之后注册，会先被派发。
     * 只有它们都没启用（栈底、抽屉关着）时才轮到这里。
     */
    private fun installBackFallback() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (onInterceptBack()) return
                    if (navController.size > 1) {
                        navController.pop()
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    companion object {
        const val ACTION_START_SCENE = "start_scene"
        const val KEY_SCENE_NAME = "stage_activity_scene_name"
        const val KEY_SCENE_ARGS = "stage_activity_scene_args"

        /** `Intent` 直接携带的 route 载荷（CBOR，见 [encodeRoute]） */
        const val KEY_SCENE_ROUTE = "stage_activity_scene_route"
    }
}

/**
 * 单一 Nav3 返回栈的渲染入口。
 *
 * 返回栈由 [EhNavController] 持有（组合之外也能压栈），组合起来之后把
 * `rememberNavBackStack` 出来的栈挂上去，攒着的压栈操作这时才回放。
 */
@Composable
private fun EhNavHostActivity.SceneStack() {
    val backStack = rememberNavBackStack(navController.entries.first())
    LaunchedEffect(backStack) {
        @Suppress("UNCHECKED_CAST")
        navController.attach(backStack as MutableList<EhRoute>)
    }
    DisposableEffect(backStack) {
        onDispose { navController.detach() }
    }
    EhSceneShell(navController, backStack)
}

/**
 * 页面外壳：原来由 `BaseScene.onViewCreated` 干的壳配置（左侧抽屉锁 / 导航勾选）
 * 搬到组合里，按当前 route 的属性应用一次。
 */
@Composable
private fun EhNavHostActivity.EhSceneShell(controller: EhNavController, backStack: List<NavKey>) {
    val route = backStack.lastOrNull() as? EhRoute
    DisposableEffect(route) {
        if (route != null) {
            hostState.setLeftDrawerEnabled(route.hasLeftDrawer)
            hostState.setNavCheckedItem(route.navCheckedItem)
        }
        onDispose {}
    }
    NavDisplay(
        backStack = backStack,
        onBack = { controller.pop() },
        entryDecorators = listOf(
            // 保证每个 entry 各自持有 rememberSaveable 状态与 ViewModel
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        // 进退场动画见 NavTransitions（来自 Matter：滑入 + 视差 + 返回时 0.8x 收缩）
        transitionSpec = { NavTransitions.forward(this) },
        popTransitionSpec = { NavTransitions.pop(this) },
        predictivePopTransitionSpec = { _ -> NavTransitions.predictivePop(this) },
        entryProvider = entryProvider {
            // 已经出壳的页面：直接是 @Composable，没有 Fragment 这一层
            entry<EhRoute.SelectSite> { SelectSiteScene(it) }
            entry<EhRoute.SignIn> { SignInScene(it) }
            entry<EhRoute.CookieSignIn> { CookieSignInScene() }
            entry<EhRoute.WebViewSignIn> { WebViewSignInScene() }
            entry<EhRoute.Security> { SecurityScene(it) }
            entry<EhRoute.History> { HistoryScene() }
            entry<EhRoute.Favorites> { FavoritesScene() }
            entry<EhRoute.Downloads> { DownloadsScene(it) }
            entry<EhRoute.GalleryComments> { GalleryCommentsScene(it) }
            entry<EhRoute.GalleryDetail> { GalleryDetailScene(it) }
            entry<EhRoute.GalleryInfo> { GalleryInfoScene(it) }
            entry<EhRoute.GalleryList> { GalleryListScene(it) }
            entry<EhRoute.GalleryPreviews> { GalleryPreviewsScene(it) }
            entry<EhRoute.Progress> { ProgressScene(it) }
            entry<EhRoute.Reader> { ReaderRoute(it) }
            // 设置：首页、九个分类子页、关于页全部就地渲染
            entry<EhRoute.Settings> { SettingsScene() }
            entry<EhRoute.SettingsCategory> { SettingsCategoryScene(it) }
            entry<EhRoute.About> { AboutScene() }
        },
    )
}
