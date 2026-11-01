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
package com.hippo.ehviewer.ui.page.security

import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.ui.compose.nav.NavResultBus
import com.hippo.ehviewer.ui.compose.nav.NavSignal
import com.hippo.ehviewer.ui.compose.nav.SignInSource
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.SignInWebViewScreen
import com.hippo.ehviewer.util.addQuicHints
import com.hippo.ehviewer.util.setDefaultSettings
import com.hippo.ehviewer.widget.DialogWebChromeClient
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import kotlinx.coroutines.CoroutineScope
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Web 登录页：一个 WebView，登录成功后发 `NavSignal.SignedIn(WEB_VIEW)` 再退出。
 *
 * P7-c 出壳：页面本身是 @Composable，WebView 通过 `AndroidView` 挂进 Compose 树
 * （见 [SignInWebViewScreen]），加载进度用顶部的 `LinearProgressIndicator`。
 * 清 cookie 的动作只在**全新进入**时做一次，旋转重建不会重来——用
 * `rememberSaveable` 的标记表达原来 `savedInstanceState == null` 那个判断。
 */
@Composable
fun WebViewSignInScene() {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialized) {
            initialized = true
            // http://stackoverflow.com/questions/32284642/how-to-handle-an-uncatched-exception
            CookieManager.getInstance().apply {
                flush()
                removeAllCookies(null)
                removeSessionCookies(null)
            }
            EhCookieStore.loadForWebView(EhUrl.URL_SIGN_IN) {
                it.name == EhCookieStore.KEY_CLOUDFLARE
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    SignInWebViewScreen(
        progress = progress,
        createWebView = {
            createWebView(
                context = context,
                scope = scope,
                onProgress = { progress = it },
                onSignedIn = {
                    NavResultBus.publish(NavSignal.SignedIn(SignInSource.WEB_VIEW))
                    navigator.pop()
                },
            ).also { webView = it }
        },
    )
}

private fun createWebView(
    context: Context,
    scope: CoroutineScope,
    onProgress: (Int) -> Unit,
    onSignedIn: () -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(Color.TRANSPARENT)
    setDefaultSettings()
    webViewClient = LoginWebViewClient(scope, onSignedIn)
    webChromeClient = object : DialogWebChromeClient(context) {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            onProgress(newProgress)
        }
    }
    addQuicHints(EhUrl.URL_SIGN_IN)
    loadUrl(EhUrl.URL_SIGN_IN)
}

private class LoginWebViewClient(
    private val scope: CoroutineScope,
    private val onSignedIn: () -> Unit,
) : WebViewClient() {
    private fun parseCookies(url: HttpUrl?, cookieStrings: String?): List<Cookie> {
        if (cookieStrings == null) {
            return emptyList()
        }
        var cookies: MutableList<Cookie>? = null
        val pieces = cookieStrings.split(";").dropLastWhile { it.isEmpty() }
        for (piece in pieces) {
            val cookie = Cookie.parse(url!!, piece) ?: continue
            if (cookies == null) {
                cookies = ArrayList()
            }
            cookies.add(cookie)
        }
        return cookies ?: emptyList()
    }

    private suspend fun addCookie(domain: String, cookie: Cookie) {
        EhCookieStore.addCookie(
            EhCookieStore.newCookie(
                cookie,
                domain,
                forcePersistent = true,
                forceLongLive = true,
                forceNotHostOnly = true,
            ),
        )
    }

    override fun onPageFinished(view: WebView, url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookieString = CookieManager.getInstance().getCookie(EhUrl.HOST_E)
        val cookies = parseCookies(httpUrl, cookieString)
        var getId = false
        var getHash = false
        for (cookie in cookies) {
            if (EhCookieStore.KEY_IPB_MEMBER_ID == cookie.name) {
                getId = true
            } else if (EhCookieStore.KEY_IPB_PASS_HASH == cookie.name) {
                getHash = true
            } else if (EhCookieStore.KEY_CLOUDFLARE == cookie.name) {
                scope.launchIO {
                    addCookie(cookie.domain, cookie)
                }
            }
        }
        if (getId && getHash) {
            scope.launchIO {
                EhUtils.signOut()
                cookies.forEach {
                    addCookie(EhUrl.DOMAIN_EX, it)
                    addCookie(EhUrl.DOMAIN_E, it)
                }
                withUIContext { onSignedIn() }
            }
        }
    }
}
