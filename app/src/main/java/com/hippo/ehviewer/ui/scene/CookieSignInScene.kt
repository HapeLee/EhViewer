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

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.exception.CloudflareBypassException
import com.hippo.ehviewer.ui.WebViewActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.NavResultBus
import com.hippo.ehviewer.ui.compose.nav.NavSignal
import com.hippo.ehviewer.ui.compose.nav.SignInSource
import com.hippo.ehviewer.ui.compose.screen.CookieSignInError
import com.hippo.ehviewer.ui.compose.screen.CookieSignInScreen
import com.hippo.network.StatusCodeException
import com.hippo.util.ExceptionUtils
import com.hippo.util.getClipboardManager
import com.hippo.util.getTextFromClipboard
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import java.util.Locale
import kotlinx.coroutines.Job
import okhttp3.Cookie

/**
 * Cookie 登录页。
 *
 * 迁移点：
 * - 表单、进度遮罩、错误对话框交给 [CookieSignInScreen]，输入与错误态用 Compose 状态
 * - 登录协程改挂 `lifecycleScope`，旋转不会丢请求；`storeCookie` 与 Cloudflare 判定不变
 * - 「从剪贴板填充」逻辑原样保留，只是把结果写进 Compose 状态后自动提交
 */
class CookieSignInScene : EhComposeScene() {

    private var memberId by mutableStateOf("")
    private var passHash by mutableStateOf("")
    private var memberIdError by mutableStateOf<String?>(null)
    private var passHashError by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<CookieSignInError?>(null)
    private var signInJob: Job? = null

    override fun needShowLeftDrawer(): Boolean = false

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.CookieSignIn> {
            CookieSignInScreen(
                memberId = memberId,
                passHash = passHash,
                memberIdError = memberIdError,
                passHashError = passHashError,
                loading = loading,
                error = error,
                onMemberIdChange = {
                    memberId = it
                    memberIdError = null
                },
                onPassHashChange = {
                    passHash = it
                    passHashError = null
                },
                onSubmit = ::enter,
                onFillFromClipboard = ::fillCookiesFromClipboard,
                onDismissError = { error = null },
                onOpenForums = {
                    context?.startActivity(
                        WebViewActivity.newIntent(requireContext(), EhUrl.URL_FORUMS),
                    )
                },
                onIgnoreError = {
                    error = null
                    ok()
                },
            )
        }
    }

    private fun enter() {
        if (signInJob?.isActive == true || loading) return
        val id = memberId.trim()
        val hash = passHash.trim()
        if (id.isEmpty()) {
            memberIdError = getString(R.string.text_is_empty)
            return
        }
        memberIdError = null
        if (hash.isEmpty()) {
            passHashError = getString(R.string.text_is_empty)
            return
        }
        passHashError = null
        hideSoftInput()
        loading = true

        signInJob = lifecycleScope.launchIO {
            EhUtils.signOut()
            val result = runCatching {
                storeCookie(id, hash)
                EhEngine.getProfile().also {
                    Settings.putDisplayName(it.displayName)
                    Settings.putAvatar(it.avatar)
                }
            }
            withUIContext {
                loading = false
                result.onSuccess {
                    ok()
                }.onFailure {
                    showResultErrorDialog(it)
                }
            }
        }
    }

    private fun ok() {
        // cookie 已落 EhCookieStore、资料已落 Settings；这里只通知父页面收尾
        NavResultBus.publish(NavSignal.SignedIn(SignInSource.COOKIE))
        finish()
    }

    private fun showResultErrorDialog(e: Throwable) {
        val isCloudflareError = e.cause is CloudflareBypassException ||
            (e is StatusCodeException && e.responseCode == 403)
        val message = buildString {
            append(ExceptionUtils.getReadableString(e))
            append("\n\n")
            append(getString(R.string.sign_in_failed_tip))
            if (isCloudflareError) {
                append("\n\n")
                append(getString(R.string.sign_in_failed_tip_2))
            }
        }
        error = CookieSignInError(message = message, canIgnore = isCloudflareError)
    }

    private suspend fun storeCookie(id: String, hash: String) {
        fun newCookie(name: String, value: String, domain: String): Cookie = Cookie.Builder().name(name).value(value)
            .domain(domain).expiresAt(Long.MAX_VALUE).build()
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_MEMBER_ID, id, EhUrl.DOMAIN_E))
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_MEMBER_ID, id, EhUrl.DOMAIN_EX))
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_PASS_HASH, hash, EhUrl.DOMAIN_E))
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_PASS_HASH, hash, EhUrl.DOMAIN_EX))
    }

    private fun fillCookiesFromClipboard() {
        val ctx = context ?: return
        fun showClipboardError() = showTip(R.string.from_clipboard_error, LENGTH_SHORT)
        hideSoftInput()

        val clipboardText = ctx.getClipboardManager().getTextFromClipboard(ctx)
        if (clipboardText.isNullOrBlank()) {
            showClipboardError()
            return
        }
        val kvs = when {
            clipboardText.contains(";") -> clipboardText.split(";")
            clipboardText.contains("\n") -> clipboardText.split("\n")
            else -> {
                showClipboardError()
                return
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }
        val hasRequiredKeys = clipboardText.contains(EhCookieStore.KEY_IPB_MEMBER_ID) &&
            clipboardText.contains(EhCookieStore.KEY_IPB_PASS_HASH)
        if (!hasRequiredKeys || kvs.size < 2) {
            showClipboardError()
            return
        }

        try {
            kvs.forEach { entry ->
                val kv = when {
                    entry.contains("=") -> entry.split("=")
                    entry.contains(":") -> entry.split(":")
                    else -> return@forEach
                }
                if (kv.size != 2) return@forEach

                val key = kv[0].trim().lowercase(Locale.getDefault())
                val value = kv[1].trim().replace(Regex("[^a-zA-Z0-9\\-_.~]"), "")
                when (key) {
                    EhCookieStore.KEY_IPB_MEMBER_ID -> memberId = value
                    EhCookieStore.KEY_IPB_PASS_HASH -> passHash = value
                }
            }
            enter()
        } catch (e: Exception) {
            ExceptionUtils.throwIfFatal(e)
            Log.e(TAG, "Error", e)
            showClipboardError()
        }
    }

    companion object {
        private const val TAG = "CookieSignInScene"
    }
}
