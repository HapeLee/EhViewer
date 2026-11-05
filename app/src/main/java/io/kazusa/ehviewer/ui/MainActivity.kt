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
package io.kazusa.ehviewer.ui

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhUrlOpener
import io.kazusa.ehviewer.client.data.ListUrlBuilder
import io.kazusa.ehviewer.client.parser.GalleryDetailUrlParser
import io.kazusa.ehviewer.client.parser.GalleryPageUrlParser
import io.kazusa.ehviewer.support.unifile.UniFile
import io.kazusa.ehviewer.support.unifile.sha1
import io.kazusa.ehviewer.support.util.addTextToClipboard
import io.kazusa.ehviewer.support.util.getClipboardManager
import io.kazusa.ehviewer.support.util.getParcelableExtraCompat
import io.kazusa.ehviewer.support.util.getUrlFromClipboard
import io.kazusa.ehviewer.support.yorozuya.SimpleHandler
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_HOMEPAGE
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_SUBSCRIPTION
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_TOP_LIST
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_WHATS_HOT
import io.kazusa.ehviewer.ui.compose.nav.ReaderSource
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.nav.galleryQueryOf
import io.kazusa.ehviewer.ui.compose.nav.homepageRoute
import io.kazusa.ehviewer.ui.compose.nav.initialCheckRoute
import io.kazusa.ehviewer.ui.compose.nav.isSolidRoute
import io.kazusa.ehviewer.ui.compose.nav.legacyRouteOf
import io.kazusa.ehviewer.ui.host.DialogHost
import io.kazusa.ehviewer.ui.host.DialogRequest
import io.kazusa.ehviewer.ui.host.EhHost
import io.kazusa.ehviewer.ui.host.EhNavHostActivity
import io.kazusa.ehviewer.ui.host.HostShell
import io.kazusa.ehviewer.ui.host.MainShell
import io.kazusa.ehviewer.ui.host.TIP_LENGTH_LONG
import kotlinx.coroutines.launch

/**
 * 主界面：抽屉 / Snackbar 已经全部是 Compose（[MainShell]），这里只剩「宿主职责」——
 * 冷启动路由、外部 Intent 解析、下载目录 / 计量网络 / App Link 三项自检，
 * 以及给页面用的 [EhHost] 壳能力实现。
 *
 * 壳状态全部是 Compose 状态：`drawerState` / `drawerEnabled` / `checkedNavId` /
 * `profileVersion`，[shell] 把它们交给 [MainShell]；页面通过 `LocalEhHost`
 * 间接改这些值，不再有 `DrawerLayout` / `NavigationView` 这些 View 引用。
 */
class MainActivity : EhNavHostActivity(), EhHost {
    private lateinit var connectivityManager: ConnectivityManager

    // 壳状态：交给 MainShell，页面通过 EhHost 改
    private val snackbarHostState = SnackbarHostState()
    private val drawerState = DrawerState(DrawerValue.Closed)
    private var drawerEnabled by mutableStateOf(true)
    private var checkedNavId by mutableIntStateOf(0)

    /** 只是「重读 Settings 里的头像 / 昵称」的触发器 */
    private var profileVersion by mutableIntStateOf(0)

    /** 工具层投递的对话框请求，由 [MainShell] 旁边的 [DialogHost] 渲染 */
    private var dialogRequest by mutableStateOf<DialogRequest?>(null)

    /**
     * 冷启动的第一跳。
     *
     * 守卫链（手势锁 → 登录 → 选站点）在这里决定开局停在哪个页面；
     * 没有守卫时就是首页列表。
     */
    override fun initialRoute(): EhRoute = initialCheckRoute()

    override fun shell(): HostShell = { content ->
        MainShell(
            drawerState = drawerState,
            drawerEnabled = drawerEnabled,
            checkedNavId = checkedNavId,
            profileVersion = profileVersion,
            snackbarHostState = snackbarHostState,
            onNavSelect = ::onNavSelect,
            onToggleNightMode = ::toggleNightMode,
            onProfileClick = ::updateProfile,
            content = content,
        )
        // 工具层（CommonOperations 等）投递来的对话框，见 DialogRequest
        dialogRequest?.let { request ->
            DialogHost(request = request, onDismiss = { dialogRequest = null })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivityManager = getSystemService()!!
        if (savedInstanceState == null) {
            checkDownloadLocation()
            if (Settings.meteredNetworkWarning) {
                checkMeteredNetwork()
            }
            if (!Settings.appLinkVerifyTip) {
                try {
                    checkAppLinkVerify()
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val action = intent.action
        if (Intent.ACTION_VIEW == action) {
            val uri = intent.data ?: return false
            val route = EhUrlOpener.parseUrl(uri.toString())
            if (route != null) {
                pushRoute(route)
                return true
            }
            // 不是画廊链接：外部「用 EhViewer 打开压缩包」
            if (uri.scheme == "file" || uri.scheme == "content") {
                pushRoute(EhRoute.Reader(uri = uri.toString(), source = ReaderSource.ARCHIVE))
                return true
            }
        } else if (Intent.ACTION_SEND == action) {
            val type = intent.type
            if ("text/plain" == type) {
                val builder = ListUrlBuilder().apply {
                    keyword = intent.getStringExtra(Intent.EXTRA_TEXT)
                }
                pushRoute(EhRoute.GalleryList(galleryQueryOf(builder)))
                return true
            } else if (type != null && type.startsWith("image/")) {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (null != uri) {
                    UniFile.fromUri(this, uri)?.sha1()?.let { hash ->
                        val builder = ListUrlBuilder().apply {
                            mode = ListUrlBuilder.MODE_IMAGE_SEARCH
                            this.hash = hash
                        }
                        pushRoute(EhRoute.GalleryList(galleryQueryOf(builder)))
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onUnrecognizedIntent(intent: Intent?) {
        if (isSolidRoute(navController.currentRoute)) {
            // TODO the intent lost
            return
        }
        if (!handleIntent(intent)) {
            var handleUrl = false
            if (intent != null && Intent.ACTION_VIEW == intent.action) {
                handleUrl = true
                if (intent.data != null) {
                    val url = intent.data.toString()
                    showDialog(
                        DialogRequest.TextInput(
                            title = getString(R.string.error_cannot_parse_the_url),
                            hint = null,
                            initialText = url,
                            positiveText = getString(android.R.string.copy),
                            onConfirm = { text, _ ->
                                this.addTextToClipboard(
                                    text,
                                    false,
                                )
                            },
                            onCancel = {},
                        ),
                    )
                }
            }
            if (0 == sceneCount) {
                if (handleUrl) {
                    finish()
                } else {
                    pushRoute(homepageRoute())
                }
            }
        }
    }

    /** 下载通知等外部入口：旧参数还原成 route，守卫链由宿主统一跑 */
    override fun onStartSceneFromIntent(sceneName: String, args: Bundle?): EhRoute? = legacyRouteOf(sceneName, args) ?: homepageRoute()

    @Throws(PackageManager.NameNotFoundException::class)
    private fun checkAppLinkVerify() {
        val manager = getSystemService(DomainVerificationManager::class.java)
        val userState = manager.getDomainVerificationUserState(packageName) ?: return
        var hasUnverified = false
        val hostToStateMap = userState.hostToStateMap
        for (key in hostToStateMap.keys) {
            val stateValue = hostToStateMap[key]
            if (stateValue == null || stateValue == DomainVerificationUserState.DOMAIN_STATE_VERIFIED || stateValue == DomainVerificationUserState.DOMAIN_STATE_SELECTED) {
                continue
            }
            hasUnverified = true
            break
        }
        if (hasUnverified) {
            // 三元对话框（打开设置 / 取消 / 不再显示）：M3 的 AlertDialog 只有两个槽位，
            // 所以走宿主对话框，见 DialogRequest.Message.neutralText
            showDialog(
                DialogRequest.Message(
                    title = getString(R.string.app_link_not_verified_title),
                    message = getString(R.string.app_link_not_verified_message),
                    positiveText = getString(R.string.open_settings),
                    negativeText = getString(android.R.string.cancel),
                    neutralText = getString(R.string.dont_show_again),
                    onPositive = ::openAppLinkSettings,
                    onNeutral = {
                        Settings.putAppLinkVerifyTip(
                            true,
                        )
                    },
                ),
            )
        }
    }

    private fun openAppLinkSettings() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                "package:$packageName".toUri(),
            )
            startActivity(intent)
        } catch (_: Throwable) {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:$packageName".toUri(),
            )
            startActivity(intent)
        }
    }

    private fun checkDownloadLocation() {
        val uniFile = Settings.downloadLocation
        // null == uniFile for first start
        if (null == uniFile || uniFile.ensureDir()) {
            return
        }
        showDialog(
            DialogRequest.Message(
                title = getString(R.string.waring),
                message = getString(R.string.invalid_download_location),
                positiveText = getString(R.string.get_it),
                onPositive = {},
            ),
        )
    }

    private fun checkMeteredNetwork() {
        if (connectivityManager.isActiveNetworkMetered) {
            lifecycleScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = getString(R.string.metered_network_warning),
                    actionLabel = getString(R.string.settings),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkClipboardUrl()
    }

    override fun onTransactScene() {
        super.onTransactScene()
        checkClipboardUrl()
    }

    private fun checkClipboardUrl() {
        SimpleHandler.getInstance().postDelayed({
            if (!isSolid) {
                checkClipboardUrlInternal()
            }
        }, 300)
    }

    /** 守卫链期间不弹剪贴板提示（原来靠 `SolidScene` 子类判断，见 [isSolidRoute]） */
    private val isSolid: Boolean
        get() = isSolidRoute(navController.currentRoute)

    private fun createRouteFromClipboardUrl(url: String): EhRoute? {
        val result1 = GalleryDetailUrlParser.parse(url, false)
        if (result1 != null) {
            return EhRoute.GalleryDetail(gid = result1.gid, token = result1.token)
        }
        val result2 = GalleryPageUrlParser.parse(url, false)
        if (result2 != null) {
            return EhRoute.Progress(
                gid = result2.gid,
                pToken = result2.pToken,
                page = result2.page,
            )
        }
        return null
    }

    private fun checkClipboardUrlInternal() {
        val text = this.getClipboardManager().getUrlFromClipboard(this)
        val hashCode = text?.hashCode() ?: 0
        if (text != null && hashCode != 0 && Settings.clipboardTextHashCode != hashCode) {
            val route = createRouteFromClipboardUrl(text)
            if (route != null) {
                lifecycleScope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = getString(R.string.clipboard_gallery_url_snack_message),
                        actionLabel = getString(R.string.clipboard_gallery_url_snack_action),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        ehNavigator().push(route)
                    }
                }
            }
        }
        Settings.putClipboardTextHashCode(hashCode)
    }

    /** 头像 / 昵称来自 `Settings`（不是 Compose 状态），改版本号让抽屉头重读一次 */
    override fun updateProfile() {
        profileVersion++
    }

    override fun setLeftDrawerEnabled(enabled: Boolean) {
        drawerEnabled = enabled
        if (!enabled && drawerState.isOpen) {
            closeLeftDrawer()
        }
    }

    /**
     * 抽屉开合要跑在带 `MonotonicFrameClock` 的上下文里：`DrawerState.animateTo` 用
     * `withFrameNanos` 驱动动画，裸的 `Dispatchers.Main`（lifecycleScope 默认）会抛
     * 「A MonotonicFrameClock is not available in this CoroutineContext」。
     */
    private fun launchOnCompose(block: suspend () -> Unit) {
        lifecycleScope.launch(AndroidUiDispatcher.Main) { block() }
    }

    override fun openLeftDrawer() = launchOnCompose { drawerState.open() }

    override fun closeLeftDrawer() = launchOnCompose { drawerState.close() }

    override fun toggleLeftDrawer() {
        if (drawerState.isOpen) closeLeftDrawer() else openLeftDrawer()
    }

    override fun setNavCheckedItem(@IdRes resId: Int) {
        checkedNavId = resId
    }

    override fun showTip(@StringRes id: Int, length: Int) {
        showTip(getString(id), length)
    }

    override fun showDialog(request: DialogRequest) {
        dialogRequest = request
    }

    /**
     * 抽屉开着的时候发 Toast：Snackbar 会被抽屉遮住，与旧实现同一取舍。
     */
    override fun showTip(message: CharSequence, length: Int) {
        if (drawerState.isOpen) {
            android.widget.Toast.makeText(
                this,
                message,
                if (length == TIP_LENGTH_LONG) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else {
            lifecycleScope.launch {
                snackbarHostState.showSnackbar(
                    message = message.toString(),
                    duration = if (length == TIP_LENGTH_LONG) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }
    }

    /** 抽屉开着时返回键先关抽屉（原 `onBackPressed` 的分支） */
    override fun onInterceptBack(): Boolean {
        if (drawerState.isOpen) {
            closeLeftDrawer()
            return true
        }
        return false
    }

    private fun onNavSelect(@IdRes id: Int) {
        // Don't select twice
        if (id == checkedNavId) return
        when (id) {
            R.id.nav_homepage -> navController.pushAsRoot(homepageRoute(LIST_ACTION_HOMEPAGE))
            R.id.nav_subscription -> navController.pushAsRoot(homepageRoute(LIST_ACTION_SUBSCRIPTION))
            R.id.nav_whats_hot -> navController.pushAsRoot(homepageRoute(LIST_ACTION_WHATS_HOT))
            R.id.nav_toplist -> navController.pushAsRoot(homepageRoute(LIST_ACTION_TOP_LIST))
            R.id.nav_favourite -> pushRoute(EhRoute.Favorites)
            R.id.nav_history -> pushRoute(EhRoute.History)
            R.id.nav_downloads -> pushRoute(EhRoute.Downloads())
            R.id.nav_settings -> pushRoute(EhRoute.Settings)
        }
        closeLeftDrawer()
    }

    /** 抽屉头的月亮按钮：跟随系统 → 深色 → 浅色 三态循环（与旧 header 一致） */
    private fun toggleNightMode() {
        val theme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES <= 0
        val target = if (((getSystemService(UI_MODE_SERVICE) as UiModeManager).nightMode == UiModeManager.MODE_NIGHT_YES) == theme) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        } else if (theme) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(target)
        Settings.putTheme(target)
        recreate()
    }
}
