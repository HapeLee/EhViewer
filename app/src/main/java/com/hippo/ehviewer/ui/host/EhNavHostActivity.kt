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

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.GravityCompat
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.EhActivity
import com.hippo.ehviewer.ui.compose.nav.EhNavigator
import com.hippo.ehviewer.ui.compose.nav.EhNavigatorProvider
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.LocalEhNavigator
import com.hippo.ehviewer.ui.compose.nav.decodeRoute
import com.hippo.ehviewer.ui.compose.nav.hasLeftDrawer
import com.hippo.ehviewer.ui.compose.nav.initialCheckRoute
import com.hippo.ehviewer.ui.compose.nav.legacyRouteOf
import com.hippo.ehviewer.ui.compose.nav.navCheckedItem
import com.hippo.ehviewer.ui.compose.reader.ReaderRoute
import com.hippo.ehviewer.ui.compose.theme.EhTheme
import com.hippo.ehviewer.ui.page.downloads.DownloadsScene
import com.hippo.ehviewer.ui.page.favorites.FavoritesScene
import com.hippo.ehviewer.ui.page.gallery.GalleryCommentsScene
import com.hippo.ehviewer.ui.page.gallery.GalleryDetailScene
import com.hippo.ehviewer.ui.page.gallery.GalleryInfoScene
import com.hippo.ehviewer.ui.page.gallery.GalleryListScene
import com.hippo.ehviewer.ui.page.gallery.GalleryPreviewsScene
import com.hippo.ehviewer.ui.page.gallery.ProgressScene
import com.hippo.ehviewer.ui.page.history.HistoryScene
import com.hippo.ehviewer.ui.page.security.CookieSignInScene
import com.hippo.ehviewer.ui.page.security.SecurityScene
import com.hippo.ehviewer.ui.page.security.SelectSiteScene
import com.hippo.ehviewer.ui.page.security.SignInScene
import com.hippo.ehviewer.ui.page.security.WebViewSignInScene
import com.hippo.ehviewer.ui.page.settings.AboutScene
import com.hippo.ehviewer.ui.page.settings.SettingsCategoryScene
import com.hippo.ehviewer.ui.page.settings.SettingsScene

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

        override fun setNavCheckedItem(resId: Int) {
            host?.setNavCheckedItem(resId)
        }

        override fun setDrawerLockMode(lockMode: Int, edgeGravity: Int) {
            host?.setDrawerLockMode(lockMode, edgeGravity)
        }

        override fun toggleDrawer(drawerGravity: Int) {
            host?.toggleDrawer(drawerGravity)
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
     * 子类在这里 `setContentView(...)` 并绑定抽屉 / Snackbar 容器。
     * 必须提供 `R.id.nav_host` 的 `ComposeView`。
     */
    protected abstract fun setupContent(savedInstanceState: Bundle?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先把根页面放进栈（组合还没起来，压栈会攒在 EhNavController 里）
        navEntries.add(initialRoute())
        setupContent(savedInstanceState)
        val composeView = findViewById<ComposeView>(R.id.nav_host)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            EhTheme {
                EhSceneDisplay(navController)
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

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (onInterceptBack()) {
            return
        }
        if (navController.size > 1) {
            navController.pop()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
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
private fun EhNavHostActivity.EhSceneDisplay(controller: EhNavController) {
    val backStack = rememberNavBackStack(controller.entries.first())
    LaunchedEffect(backStack) {
        @Suppress("UNCHECKED_CAST")
        controller.attach(backStack as MutableList<EhRoute>)
    }
    DisposableEffect(backStack) {
        onDispose { controller.detach() }
    }
    CompositionLocalProvider(
        LocalEhHost provides hostState,
        LocalEhNavigator provides controller,
    ) {
        EhSceneShell(controller, backStack)
    }
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
            hostState.setDrawerLockMode(
                if (route.hasLeftDrawer) {
                    DrawerLayout.LOCK_MODE_UNLOCKED
                } else {
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                },
                GravityCompat.START,
            )
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
            // 设置：首页与关于页已经是纯 Compose，分类页过渡期仍由 SettingsActivity 承载
            entry<EhRoute.Settings> { SettingsScene() }
            entry<EhRoute.SettingsCategory> { SettingsCategoryScene(it) }
            entry<EhRoute.About> { AboutScene() }
        },
    )
}
