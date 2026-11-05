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
package io.kazusa.ehviewer.ui.page.settings

import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.EhCookieStore
import io.kazusa.ehviewer.client.EhUrl
import io.kazusa.ehviewer.support.util.launchIO
import io.kazusa.ehviewer.ui.compose.nav.SettingsPage
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.screen.settings.WebEditorScreen
import io.kazusa.ehviewer.ui.compose.theme.ehWindowBackground
import io.kazusa.ehviewer.ui.host.TIP_LENGTH_LONG
import io.kazusa.ehviewer.ui.host.ehHostOrNoOp
import io.kazusa.ehviewer.util.addQuicHints
import io.kazusa.ehviewer.util.setDefaultSettings
import io.kazusa.ehviewer.widget.DialogWebChromeClient
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 站内 WebView 编辑器（原 `UConfigFragment` / `MyTagsFragment`，两个 160 行的 Fragment）。
 *
 * 两者的差别只有三处，都写在 [WebEditorConfig] 里：
 * - UConfig 有「应用」动作（点页面上的 apply 按钮）与 `apply_tip` 提示；
 * - UConfig 不跟随站内跳转（任何链接都不加载），我的标签允许同 host 的跳转；
 * - UConfig 离开时要把 `cloudflare` / `settings_profile` 两个 cookie 长期化写回 CookieJar，
 *   否则下次进来又要过一遍盾。
 *
 * cookie 仍旧是「进页面前把 okhttp 的 cookie 灌进 WebView、离开前把 WebView 的灌回去」。
 * 灌回去走全局 `launchIO`：`rememberCoroutineScope` 在离屏那一刻就被取消了，用它写盘会丢。
 */
@Composable
fun WebEditorScene(page: SettingsPage) {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val config = remember(page) {
        if (page == SettingsPage.UCONFIG) {
            WebEditorConfig(
                url = EhUrl.uConfigUrl,
                titleRes = R.string.u_config,
                showApply = true,
                allowSameHostNavigation = false,
                persistProfileCookies = true,
            )
        } else {
            WebEditorConfig(
                url = EhUrl.myTagsUrl,
                titleRes = R.string.my_tags,
                showApply = false,
                allowSameHostNavigation = true,
                persistProfileCookies = false,
            )
        }
    }

    var progress by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(config.url) {
        if (config.showApply) host.showTip(R.string.apply_tip, TIP_LENGTH_LONG)
    }

    DisposableEffect(config.url) {
        onDispose {
            webView?.destroy()
            webView = null
            if (config.persistProfileCookies) {
                persistProfileCookies(config.url)
            }
        }
    }

    WebEditorScreen(
        titleRes = config.titleRes,
        progress = progress,
        onBack = navigator::pop,
        onApply = if (config.showApply) {
            { if (loaded) webView?.loadUrl(APPLY_SCRIPT) }
        } else {
            null
        },
        createWebView = {
            createWebView(
                context = context,
                config = config,
                onProgress = { progress = it },
                onPageStarted = { loaded = false },
                onPageFinished = { loaded = true },
            ).also { webView = it }
        },
    )
}

private fun createWebView(
    context: Context,
    config: WebEditorConfig,
    onProgress: (Int) -> Unit,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit,
): WebView {
    // 进页面之前先把 okhttp 的 cookie 灌给 WebView（老实现同样在 onCreate 里做）
    CookieManager.getInstance().apply {
        flush()
        removeAllCookies(null)
        removeSessionCookies(null)
        for (cookie in EhCookieStore.getCookies(config.url.toHttpUrl())) {
            setCookie(config.url, cookie.toString())
        }
    }

    return WebView(context).apply {
        // XML 主题删掉之后没有 `?android:attr/colorBackground` 可读，统一从色板取
        setBackgroundColor(ehWindowBackground(context))
        setDefaultSettings()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // UConfig 只认自己那一个页面；我的标签允许同 host 内跳转
                return if (config.allowSameHostNavigation) {
                    !request.url.toString().startsWith(config.url)
                } else {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onPageStarted()
            }

            override fun onPageFinished(view: WebView, url: String) {
                onPageFinished()
            }
        }
        webChromeClient = object : DialogWebChromeClient(context) {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgress(newProgress)
            }
        }
        addQuicHints(config.url)
        loadUrl(config.url)
    }
}

/** 离开 UConfig 时把盾与配置 profile 的 cookie 长期化写回（老 `onDestroyView`） */
private fun persistProfileCookies(url: String) {
    val cookiesString = CookieManager.getInstance().getCookie(url)
    if (cookiesString.isNullOrEmpty()) return
    val hostUrl = EhUrl.host.toHttpUrl()
    launchIO {
        EhCookieStore.deleteCookie(hostUrl, EhCookieStore.KEY_SETTINGS_PROFILE)
        for (header in cookiesString.split(";".toRegex()).dropLastWhile { it.isEmpty() }) {
            val cookie = Cookie.parse(hostUrl, header) ?: continue
            if (cookie.name == EhCookieStore.KEY_CLOUDFLARE || cookie.name == EhCookieStore.KEY_SETTINGS_PROFILE) {
                EhCookieStore.addCookie(
                    Cookie.Builder()
                        .name(cookie.name)
                        .value(cookie.value)
                        .domain(cookie.domain)
                        .path(cookie.path)
                        .expiresAt(Long.MAX_VALUE)
                        .build(),
                )
            }
        }
    }
}

private class WebEditorConfig(
    val url: String,
    val titleRes: Int,
    val showApply: Boolean,
    val allowSameHostNavigation: Boolean,
    val persistProfileCookies: Boolean,
)

private const val APPLY_SCRIPT = "javascript: document.getElementById('apply').children[0].click();"
