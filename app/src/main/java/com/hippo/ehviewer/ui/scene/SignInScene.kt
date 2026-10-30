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
package com.hippo.ehviewer.ui.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.ui.WebViewActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.CHECK_STEP_SIGN_IN
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.NavResultBus
import com.hippo.ehviewer.ui.compose.nav.NavSignal
import com.hippo.ehviewer.ui.compose.nav.SignInSource
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.nav.nextCheckRoute
import com.hippo.ehviewer.ui.compose.nav.targetOf
import com.hippo.ehviewer.ui.compose.screen.SignInScreen
import com.hippo.util.ExceptionUtils
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import kotlinx.coroutines.launch

/**
 * 登录页。
 *
 * 迁移点：
 * - 表单与进度遮罩交给 [SignInScreen]，`TextInputLayout` 的错误状态改成 Compose 状态
 * - 登录/取资料/收尾三步流程与旧实现一致（含 `star` cookie、uconfig、sad panda 检测），
 *   仍在 Scene 的 `lifecycleScope` 上跑，旋转不会丢请求
 * - 子页（WebView / Cookie）登录完成后只发一条 `NavSignal.SignedIn`，
 *   这里在 `onResume` 取一次决定补 `getProfile()` 还是 `finishSignIn()`
 */
class SignInScene : EhComposeScene() {

    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var usernameError by mutableStateOf<String?>(null)
    private var passwordError by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun needShowLeftDrawer(): Boolean = false

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.SignIn> {
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
                onRegister = {
                    context?.let { UrlOpener.openUrl(it, EhUrl.URL_REGISTER, false) }
                },
                onSignInViaWebView = {
                    ehNavigator().push(EhRoute.WebViewSignIn)
                },
                onSignInViaCookies = {
                    ehNavigator().push(EhRoute.CookieSignIn)
                },
                onSkipSignIn = ::skipSignIn,
                onDismissError = { errorMessage = null },
                onOpenForums = {
                    context?.startActivity(WebViewActivity.newIntent(requireContext(), EhUrl.URL_FORUMS))
                },
            )
        }
    }

    /**
     * 收子页的登录结果。
     *
     * 子页（WebView / Cookie）把 cookie 落 `EhCookieStore`、资料落 `Settings` 之后
     * 只发一条 [NavSignal.SignedIn]；这里回到前台时取一次，决定补哪一步收尾。
     */
    override fun onResume() {
        super.onResume()
        when (val signal = NavResultBus.consume()) {
            is NavSignal.SignedIn -> when (signal.source) {
                SignInSource.WEB_VIEW -> getProfile()
                SignInSource.COOKIE, SignInSource.PASSWORD -> finishSignIn()
            }
            null -> Unit
        }
    }

    private fun showResultErrorDialog(e: Throwable) {
        loading = false
        errorMessage = ExceptionUtils.getReadableString(e)
    }

    private fun signIn() {
        if (loading) return
        val name = username
        val pwd = password
        if (name.isEmpty()) {
            usernameError = getString(R.string.error_username_cannot_empty)
            return
        }
        usernameError = null
        if (pwd.isEmpty()) {
            passwordError = getString(R.string.error_password_cannot_empty)
            return
        }
        passwordError = null
        hideSoftInput()
        loading = true
        lifecycleScope.launchIO {
            EhUtils.signOut()
            runCatching {
                EhEngine.signIn(name, pwd)
            }.onFailure {
                withUIContext { showResultErrorDialog(it) }
            }.onSuccess {
                getProfile()
            }
        }
    }

    private fun getProfile() {
        lifecycleScope.launchIO {
            withUIContext { loading = true }
            runCatching {
                EhEngine.getProfile().run {
                    Settings.putDisplayName(displayName)
                    Settings.putAvatar(avatar)
                }
            }.onFailure {
                withUIContext { showResultErrorDialog(it) }
            }.onSuccess {
                finishSignIn()
            }
        }
    }

    private fun skipSignIn() {
        loading = true
        lifecycleScope.launchIO {
            EhUtils.signOut()
            // 跳过登录时把画廊站点固定成 E 站
            Settings.putGallerySite(EhUrl.SITE_E)
            Settings.putSelectSite(false)
            withUIContext { finishSignIn(false) }
        }
    }

    private fun finishSignIn(signedIn: Boolean = true) {
        lifecycleScope.launchIO {
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
                updateAvatar()
                // 登录是守卫链的一跳：用新的落点替换掉登录页本身
                ehNavigator().replaceTop(nextCheckRoute(CHECK_STEP_SIGN_IN, targetOf(ehRoute)))
            }
        }
    }
}
