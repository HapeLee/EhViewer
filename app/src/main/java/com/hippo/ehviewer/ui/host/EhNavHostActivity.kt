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
import android.app.assist.AssistContent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.GravityCompat
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
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
import com.hippo.ehviewer.ui.compose.nav.cachedGalleryDetail
import com.hippo.ehviewer.ui.compose.nav.compatSceneClassNameOf
import com.hippo.ehviewer.ui.compose.nav.compatSceneClassOf
import com.hippo.ehviewer.ui.compose.nav.compatSceneKeyOf
import com.hippo.ehviewer.ui.compose.nav.hasLeftDrawer
import com.hippo.ehviewer.ui.compose.nav.initialCheckRoute
import com.hippo.ehviewer.ui.compose.nav.legacyRouteOf
import com.hippo.ehviewer.ui.compose.nav.navCheckedItem
import com.hippo.ehviewer.ui.compose.nav.routeOf
import com.hippo.ehviewer.ui.compose.nav.toLegacyArgs
import com.hippo.ehviewer.ui.compose.theme.EhTheme
import com.hippo.ehviewer.ui.scene.CookieSignInScene
import com.hippo.ehviewer.ui.scene.SecurityScene
import com.hippo.ehviewer.ui.scene.SelectSiteScene
import com.hippo.ehviewer.ui.scene.SignInScene
import com.hippo.ehviewer.ui.scene.WebViewSignInScene
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.scene.SceneHost

/**
 * 应用级导航宿主：一个 Activity、一棵 Compose 树、一个 [NavDisplay] 返回栈。
 *
 * 旧实现是「Activity 里的 Fragment 场景栈」，页面内部再各自套一个 Nav3 栈，
 * 返回键要在两层之间判断。这里把两层合成一层：
 * 页面就是返回栈里的 entry，[EhNavController] 是唯一状态源。
 */
abstract class EhNavHostActivity : EhActivity(), SceneHost, EhNavigatorProvider {
    private val navEntries = mutableStateListOf<NavKey>()
    private val sceneIndex = HashMap<String, SceneFragment>()

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
        val clazzStr = intent.getStringExtra(KEY_SCENE_NAME) ?: return false
        val clazz: Class<*> = try {
            Class.forName(clazzStr)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Can't find class $clazzStr", e)
            return false
        }
        val args = intent.getBundleExtra(KEY_SCENE_ARGS)
        val route = onStartSceneFromIntent(clazz, args) ?: return false
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
     * @return `null` for do not start scene
     */
    protected open fun onStartSceneFromIntent(clazz: Class<*>, args: Bundle?): EhRoute? = legacyRouteOf(clazz.name, args)

    /**
     * Can't recognize intent in first time `onCreate` and `onNewIntent`,
     * null included.
     */
    protected open fun onUnrecognizedIntent(intent: Intent?) {}

    /** 每次压栈之后回调；旧实现用它延迟检查剪贴板 */
    protected open fun onTransactScene() {}

    /** 子类可以在这里拦下返回键（抽屉开着时先关抽屉） */
    protected open fun onInterceptBack(): Boolean = false

    override val sceneCount: Int
        get() = navController.size

    override val topSceneClass: Class<*>?
        get() = navController.currentRoute?.let(::compatSceneClassOf)

    override val topScene: SceneFragment?
        get() {
            val route = navController.currentRoute ?: return null
            return compatSceneKeyOf(route)?.let { sceneIndex[it] }
        }

    override fun indexOfScene(scene: SceneFragment): Int {
        val route = routeOf(scene) ?: return -1
        return navController.indexOf(route)
    }

    override fun startScene(announcer: Announcer, horizontal: Boolean) {
        pushRoute(routeOf(announcer))
    }

    override fun startSceneFirstly(announcer: Announcer) {
        navController.clearAboveRoot()
        pushRoute(routeOf(announcer))
    }

    override fun finishScene(scene: SceneFragment) {
        val route = routeOf(scene) ?: return
        // 栈底不弹出，改成收掉整个 Activity——与旧 StageActivity 的行为一致
        if (navController.size > 1) {
            navController.popRoute(route)
        } else {
            finish()
        }
    }

    override fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?) {
        val route = routeOf(scene) ?: return
        compatSceneKeyOf(route)?.let { sceneIndex[it] = scene }
    }

    override fun onSceneViewDestroyed(scene: SceneFragment) {
        val route = routeOf(scene)
        if (route != null) {
            compatSceneKeyOf(route)?.let { sceneIndex.remove(it) }
        }
    }

    override fun onSceneDestroyed(scene: SceneFragment) {
        val route = routeOf(scene)
        if (route != null) {
            compatSceneKeyOf(route)?.let { sceneIndex.remove(it) }
        }
    }

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
        val scene = topScene
        if (scene != null) {
            // 默认实现走 finish() → finishScene()；页面也可以自己拦下（比如收起搜索框）
            scene.onBackPressed()
            return
        }
        if (navController.size > 1) {
            navController.pop()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        topScene?.onProvideAssistContent(outContent)
    }

    companion object {
        const val ACTION_START_SCENE = "start_scene"
        const val KEY_SCENE_NAME = "stage_activity_scene_name"
        const val KEY_SCENE_ARGS = "stage_activity_scene_args"
        private val TAG = EhNavHostActivity::class.java.simpleName
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

            // 迁移期其余页面仍是旧 Scene，统一走兼容承载（P7-c 后逐条替换）
            entry<EhRoute.Favorites> { CompatSceneEntry(it) }
            entry<EhRoute.History> { CompatSceneEntry(it) }
            entry<EhRoute.Downloads> { CompatSceneEntry(it) }
            entry<EhRoute.GalleryList> { CompatSceneEntry(it) }
            entry<EhRoute.GalleryDetail> { CompatSceneEntry(it) }
            entry<EhRoute.GalleryInfo> { CompatSceneEntry(it) }
            entry<EhRoute.GalleryPreviews> { CompatSceneEntry(it) }
            entry<EhRoute.GalleryComments> { CompatSceneEntry(it) }
            entry<EhRoute.Progress> { CompatSceneEntry(it) }
            entry<EhRoute.Reader> { CompatSceneEntry(it) }
        },
    )
}

/**
 * 兼容承载：把一个还没出壳的旧 `SceneFragment` 当成 Nav3 的一个 entry 渲染。
 *
 * 每个 entry 一个 `FragmentContainerView`：fragment 由 `FragmentFactory` 反射创建，
 * 入参由 route 编解码得到（[toLegacyArgs]）。entry 离开返回栈时立刻 remove fragment，
 * 所以生命周期与 entry 严格一致——这是双栈时代最缺的一条保证。
 *
 * P7-c 把页面换成纯 @Composable 之后，这个组合项整体删掉。
 */
@Composable
internal fun CompatSceneEntry(route: EhRoute, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fragmentManager = remember(context) { (context as FragmentActivity).supportFragmentManager }
    val className = compatSceneClassNameOf(route) ?: return
    // tag / 容器 id 用带参数的 key，反射实例化仍然要类名
    val key = compatSceneKeyOf(route) ?: return
    val container = remember(fragmentManager, key) {
        FragmentContainerView(context).apply { id = compatContainerIdOf(key) }
    }
    DisposableEffect(fragmentManager, key) {
        // 等 View 挂上窗口再提交事务，避免打断 FragmentManager 正在执行的批次
        val attach = Runnable {
            if (fragmentManager.isStateSaved) return@Runnable
            val existing = fragmentManager.findFragmentByTag(key)
            val fragment = existing ?: fragmentManager.fragmentFactory
                .instantiate(context.classLoader, className)
                .apply { arguments = route.toLegacyArgs(cachedGalleryDetail(route)) }
            // 旋转重建时 fragment 由 FragmentManager 先恢复出来（还没有 View），
            // 这里重新 add 一次把 View 挂到新容器上
            if (fragment.view?.parent !== container) {
                fragmentManager.commitNow { add(container, fragment, key) }
            }
        }
        container.post(attach)
        onDispose {
            container.removeCallbacks(attach)
            val fragment = fragmentManager.findFragmentByTag(key)
            if (fragment != null && !fragmentManager.isStateSaved) {
                fragmentManager.commitNow { remove(fragment) }
            }
        }
    }
    AndroidView(modifier = modifier, factory = { container })
}

/**
 * 兼容承载容器的 View id。
 *
 * 不能用 `View.generateViewId()`：旋转重建时 FragmentManager 会先把旧 fragment
 * 恢复出来，它记着的 `mContainerId` 必须能在新视图树里找到，否则 fragment 一直拿不到
 * 容器、页面就是一片空白。所以这里用「类名哈希 + 固定高位」推导出稳定 id——
 * 高位取 `0x01` 保证不会撞上 `R.id.*`（都是 `0x7f` 开头）。
 */
private fun compatContainerIdOf(key: String): Int = 0x01000000 or (key.hashCode() and 0x00FFFFFF)
