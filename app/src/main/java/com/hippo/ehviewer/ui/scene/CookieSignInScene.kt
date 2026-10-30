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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.exception.CloudflareBypassException
import com.hippo.ehviewer.ui.WebViewActivity
import com.hippo.ehviewer.ui.compose.nav.NavResultBus
import com.hippo.ehviewer.ui.compose.nav.NavSignal
import com.hippo.ehviewer.ui.compose.nav.SignInSource
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.CookieSignInError
import com.hippo.ehviewer.ui.compose.screen.CookieSignInScreen
import com.hippo.ehviewer.ui.host.ehHostOrNoOp
import com.hippo.network.StatusCodeException
import com.hippo.util.ExceptionUtils
import com.hippo.util.getClipboardManager
import com.hippo.util.getTextFromClipboard
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import java.util.Locale
import okhttp3.Cookie

/**
 * Cookie 登录页。
 *
 * P7-c 出壳：表单、进度遮罩、错误对话框仍是 [CookieSignInScreen]，
 * 但页面本身不再有 Fragment；登录协程挂 `rememberCoroutineScope`，
 * `storeCookie` 与 Cloudflare 判定不变。
 * 「从剪贴板填充」逻辑原样保留，只是把结果写进组合状态后自动提交。
 */
@Composable
fun CookieSignInScene() {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val host = ehHostOrNoOp()
    val scope = rememberCoroutineScope()

    var memberId by rememberSaveable { mutableStateOf("") }
    var passHash by rememberSaveable { mutableStateOf("") }
    var memberIdError by remember { mutableStateOf<String?>(null) }
    var passHashError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<CookieSignInError?>(null) }

    suspend fun storeCookie(id: String, hash: String) {
        fun newCookie(name: String, value: String, domain: String): Cookie = Cookie.Builder().name(name).value(value)
            .domain(domain).expiresAt(Long.MAX_VALUE).build()
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_MEMBER_ID, id, EhUrl.DOMAIN_E))
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_MEMBER_ID, id, EhUrl.DOMAIN_EX))
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_PASS_HASH, hash, EhUrl.DOMAIN_E))
        EhCookieStore.addCookie(newCookie(EhCookieStore.KEY_IPB_PASS_HASH, hash, EhUrl.DOMAIN_EX))
    }

    fun ok() {
        // cookie 已落 EhCookieStore、资料已落 Settings；这里只通知父页面收尾
        NavResultBus.publish(NavSignal.SignedIn(SignInSource.COOKIE))
        navigator.pop()
    }

    fun showResultError(e: Throwable) {
        val isCloudflareError = e.cause is CloudflareBypassException ||
            (e is StatusCodeException && e.responseCode == 403)
        val message = buildString {
            append(ExceptionUtils.getReadableString(e))
            append("\n\n")
            append(context.getString(R.string.sign_in_failed_tip))
            if (isCloudflareError) {
                append("\n\n")
                append(context.getString(R.string.sign_in_failed_tip_2))
            }
        }
        error = CookieSignInError(message = message, canIgnore = isCloudflareError)
    }

    fun enter() {
        if (loading) return
        val id = memberId.trim()
        val hash = passHash.trim()
        if (id.isEmpty()) {
            memberIdError = context.getString(R.string.text_is_empty)
            return
        }
        memberIdError = null
        if (hash.isEmpty()) {
            passHashError = context.getString(R.string.text_is_empty)
            return
        }
        passHashError = null
        host.hideSoftInput()
        loading = true

        scope.launchIO {
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
                    showResultError(it)
                }
            }
        }
    }

    fun fillCookiesFromClipboard() {
        fun showClipboardError() = host.showTip(R.string.from_clipboard_error, BaseScene.LENGTH_SHORT)
        host.hideSoftInput()

        val clipboardText = context.getClipboardManager().getTextFromClipboard(context)
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
            Log.e("CookieSignInScene", "Error", e)
            showClipboardError()
        }
    }

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
            context.startActivity(WebViewActivity.newIntent(context, EhUrl.URL_FORUMS))
        },
        onIgnoreError = {
            error = null
            ok()
        },
    )
}
