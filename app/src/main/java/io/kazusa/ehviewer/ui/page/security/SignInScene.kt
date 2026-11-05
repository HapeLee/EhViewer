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
package io.kazusa.ehviewer.ui.page.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.UrlOpener
import io.kazusa.ehviewer.client.EhCookieStore
import io.kazusa.ehviewer.client.EhEngine
import io.kazusa.ehviewer.client.EhUrl
import io.kazusa.ehviewer.client.EhUtils
import io.kazusa.ehviewer.support.util.ExceptionUtils
import io.kazusa.ehviewer.support.util.launchIO
import io.kazusa.ehviewer.support.util.withUIContext
import io.kazusa.ehviewer.ui.WebViewActivity
import io.kazusa.ehviewer.ui.compose.nav.CHECK_STEP_SIGN_IN
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.NavResultBus
import io.kazusa.ehviewer.ui.compose.nav.NavSignal
import io.kazusa.ehviewer.ui.compose.nav.SignInSource
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.nav.nextCheckRoute
import io.kazusa.ehviewer.ui.compose.nav.targetOf
import io.kazusa.ehviewer.ui.compose.screen.SignInScreen
import io.kazusa.ehviewer.ui.host.OnHostResume
import io.kazusa.ehviewer.ui.host.ehHostOrNoOp
import kotlinx.coroutines.launch

/**
 * 登录页。
 *
 * P7-c 出壳：不再是 Fragment，页面的输入与进度就是组合里的状态；
 * 登录 / 取资料 / 收尾三步流程与旧实现一致（含 `star` cookie、uconfig、sad panda 检测）。
 * 协程挂 `rememberCoroutineScope`，旋转会取消请求——旧实现挂 `lifecycleScope`，
 * 但 Fragment 重建后 `loading` 状态本来就丢了，所以两者对用户可见的结果一样。
 *
 * 子页（WebView / Cookie）登录完成后只发一条 `NavSignal.SignedIn`，
 * 这里在回到前台时取一次，决定补 `getProfile()` 还是 `finishSignIn()`。
 */
@Composable
fun SignInScene(route: EhRoute.SignIn) {
    val context = LocalContext.current
    // 事件回调里读资源：LocalContext.current 不随 Configuration 变化失效，
    // 换 LocalResources.current（lint 的 LocalContextGetResourceValueCall）
    val resources = LocalResources.current
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val scope = rememberCoroutineScope()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun showResultError(e: Throwable) {
        loading = false
        errorMessage = ExceptionUtils.getReadableString(e)
    }

    fun finishSignIn(signedIn: Boolean = true) {
        scope.launchIO {
            withUIContext { loading = true }
            if (signedIn) {
                runCatching {
                    // For the `star` cookie, https://github.com/Ehviewer-Overhauled/Ehviewer/issues/873
                    EhEngine.getNews(false)
                    EhCookieStore.copyCookie(EhUrl.DOMAIN_E, EhUrl.DOMAIN_EX, EhCookieStore.KEY_STAR)

                    // Get cookies for image limits
                    launch { runCatching { EhEngine.getUConfig(EhUrl.URL_UCONFIG_E) } }

                    // Sad panda check
                    EhEngine.getUConfig(EhUrl.URL_UCONFIG_EX)
                }.onFailure {
                    Settings.putGallerySite(EhUrl.SITE_E)
                    Settings.putSelectSite(false)
                }
            }
            withUIContext {
                Settings.putNeedSignIn(false)
                host.updateProfile()
                // 登录是守卫链的一跳：用新的落点替换掉登录页本身
                navigator.replaceTop(nextCheckRoute(CHECK_STEP_SIGN_IN, targetOf(route)))
            }
        }
    }

    fun getProfile() {
        scope.launchIO {
            withUIContext { loading = true }
            runCatching {
                EhEngine.getProfile().run {
                    Settings.putDisplayName(displayName)
                    Settings.putAvatar(avatar)
                }
            }.onFailure {
                withUIContext { showResultError(it) }
            }.onSuccess {
                finishSignIn()
            }
        }
    }

    fun signIn() {
        if (loading) return
        val name = username
        val pwd = password
        if (name.isEmpty()) {
            usernameError = resources.getString(R.string.error_username_cannot_empty)
            return
        }
        usernameError = null
        if (pwd.isEmpty()) {
            passwordError = resources.getString(R.string.error_password_cannot_empty)
            return
        }
        passwordError = null
        host.hideSoftInput()
        loading = true
        scope.launchIO {
            EhUtils.signOut()
            runCatching {
                EhEngine.signIn(name, pwd)
            }.onFailure {
                withUIContext { showResultError(it) }
            }.onSuccess {
                getProfile()
            }
        }
    }

    fun skipSignIn() {
        loading = true
        scope.launchIO {
            EhUtils.signOut()
            // 跳过登录时把画廊站点固定成 E 站
            Settings.putGallerySite(EhUrl.SITE_E)
            Settings.putSelectSite(false)
            withUIContext { finishSignIn(false) }
        }
    }

    OnHostResume {
        when (val signal = NavResultBus.consume()) {
            is NavSignal.SignedIn -> when (signal.source) {
                SignInSource.WEB_VIEW -> getProfile()
                SignInSource.COOKIE, SignInSource.PASSWORD -> finishSignIn()
            }
            null -> Unit
        }
    }

    SignInScreen(
        username = username,
        password = password,
        usernameError = usernameError,
        passwordError = passwordError,
        loading = loading,
        errorMessage = errorMessage,
        onUsernameChange = {
            username = it
            usernameError = null
        },
        onPasswordChange = {
            password = it
            passwordError = null
        },
        onSignIn = ::signIn,
        onRegister = { UrlOpener.openUrl(context, EhUrl.URL_REGISTER, false) },
        onSignInViaWebView = { navigator.push(EhRoute.WebViewSignIn) },
        onSignInViaCookies = { navigator.push(EhRoute.CookieSignIn) },
        onSkipSignIn = ::skipSignIn,
        onDismissError = { errorMessage = null },
        onOpenForums = { context.startActivity(WebViewActivity.newIntent(context, EhUrl.URL_FORUMS)) },
    )
}
