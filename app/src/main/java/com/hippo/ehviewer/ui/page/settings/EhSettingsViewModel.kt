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
package com.hippo.ehviewer.ui.page.settings

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.parser.HomeParser
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.util.ReadableTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Eh 设置页里两个「有网络、有对话框」的项：账号与图片限额。
 *
 * 老实现是 `AccountPreference` / `ImageLimitsPreference` 两个 `DialogPreference`
 * （各 130~170 行，把 `SettingsActivity`、`lifecycleScope`、`AlertDialog` 缠在一起）。
 * 迁到 Compose 之后状态集中在这里，页面只负责画；旋转不再重发请求，网络异常也只更新
 * 对话框里的文案（与原来 `onFailure { mDialog.setMessage(it.message) }` 一致）。
 */
class EhSettingsViewModel(private val app: Application) : ViewModel() {
    /** 登录态决定哪些项显示，进页面时读一次（与 `EhFragment.onSetupPreferences` 同一时机） */
    val signedIn: Boolean = EhCookieStore.hasSignedIn()
    val displayName: String? = Settings.displayName
    val tagTranslatable: Boolean = EhTagDatabase.isTranslatable(app)

    /** 图片限额那一行的摘要：`Loading…` → `Current: x / y` */
    var imageLimitsSummary by mutableStateOf(app.getString(R.string.settings_eh_image_limits_summary))
        private set

    var limitsMessage by mutableStateOf("")
        private set
    var limitsActionLabel by mutableStateOf(app.getString(R.string.settings_eh_reset))
        private set
    var limitsActionEnabled by mutableStateOf(false)
        private set

    var accountMessage by mutableStateOf("")
        private set

    /** 身份 Cookie 原文，供「复制」用；未登录时为 `null` */
    var accountCookie by mutableStateOf<String?>(null)
        private set

    /** 限额重置成功的计数：页面按「变了几次」来弹提示（比一次性布尔更不容易漏事件） */
    var limitsResetCount by mutableIntStateOf(0)
        private set

    private var limits: HomeParser.Limits? = null
    private var funds: HomeParser.Funds? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (signedIn) fetchLimits(showError = false)
        refreshAccount()
    }

    /** 打开对话框：先占位，没有数据才发请求（老实现在 `onDialogCreated` 里同样处理） */
    fun openLimits() {
        limitsMessage = app.getString(R.string.please_wait)
        limitsActionEnabled = false
        if (limits == null) fetchLimits(showError = true) else bindLimits()
    }

    fun resetLimits() {
        val current = limits ?: return
        limitsActionEnabled = false
        limitsMessage = app.getString(R.string.please_wait)
        viewModelScope.launch {
            runCatching { EhEngine.resetImageLimits(current.maximum < 0) }
                .onSuccess {
                    // 限额存在 E 站 cookie 的 quota 上，重置后同步到 EX
                    EhCookieStore.copyCookie(EhUrl.DOMAIN_E, EhUrl.DOMAIN_EX, EhCookieStore.KEY_QUOTA)
                    limits = it
                    bindLimits()
                    limitsResetCount++
                }
                .onFailure { limitsMessage = it.readableMessage() }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            EhUtils.signOut()
            // 与老实现一致：留 1.5s 让提示看清，再从干净的栈回到首页（登录守卫会重新接管）
            delay(1500)
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }

    fun refreshIgneous() {
        accountMessage = app.getString(R.string.please_wait)
        viewModelScope.launch {
            EhCookieStore.deleteCookie(EhUrl.HOST_EX.toHttpUrl(), EhCookieStore.KEY_IGNEOUS)
            runCatching { EhEngine.getUConfig(EhUrl.URL_UCONFIG_EX) }
            refreshAccount()
        }
    }

    private fun fetchLimits(showError: Boolean) {
        viewModelScope.launch {
            runCatching { EhEngine.getImageLimits() }
                .onSuccess {
                    limits = it.limits
                    funds = it.funds
                    bindLimits()
                }
                .onFailure {
                    if (showError) limitsMessage = it.readableMessage()
                }
        }
    }

    private fun bindLimits() {
        val limits = limits ?: return
        val funds = funds ?: return
        var quotaExpire = 0L
        EhCookieStore.getCookies(EhUrl.HOST_E.toHttpUrl()).forEach {
            if (it.name == EhCookieStore.KEY_QUOTA) quotaExpire = it.expiresAt
        }
        val summary = formatLimits(limits)
        var message = summary
        if (quotaExpire > 0) {
            message += "  (~" + ReadableTime.getShortTime(quotaExpire) + ")"
        }
        message += "\n" + if (limits.maximum < 0) {
            app.getString(R.string.settings_eh_unlock_cost, limits.resetCost)
        } else {
            app.getString(R.string.settings_eh_reset_cost, limits.resetCost)
        }
        message += "\n" + app.getString(R.string.current_funds, "${funds.fundsGP}+", funds.fundsC)
        limitsMessage = message
        limitsActionLabel = app.getString(
            if (limits.maximum < 0) R.string.settings_eh_unlock else R.string.settings_eh_reset,
        )
        limitsActionEnabled = limits.resetCost != 0
        imageLimitsSummary = summary
    }

    private fun formatLimits(limits: HomeParser.Limits): String = when (limits.maximum) {
        HomeParser.IP_NORMAL ->
            app.getString(R.string.settings_eh_image_limits_summary_ip) +
                app.getString(R.string.settings_eh_image_limits_summary_ip_ok)
        HomeParser.IP_RESTRICTED ->
            app.getString(R.string.settings_eh_image_limits_summary_ip) +
                app.getString(R.string.settings_eh_image_limits_summary_ip_restricted)
        else -> app.getString(R.string.settings_eh_image_limits_summary_acc, limits.current, limits.maximum)
    }

    private fun refreshAccount() {
        if (!EhCookieStore.hasSignedIn()) {
            accountCookie = null
            accountMessage = app.getString(R.string.settings_eh_account_name_tourist)
            return
        }
        var ipbMemberId: String? = null
        var ipbPassHash: String? = null
        var igneous: String? = null
        var igneousExpire = 0L
        EhCookieStore.getCookies(EhUrl.HOST_EX.toHttpUrl()).forEach {
            when (it.name) {
                EhCookieStore.KEY_IPB_MEMBER_ID -> ipbMemberId = it.value
                EhCookieStore.KEY_IPB_PASS_HASH -> ipbPassHash = it.value
                EhCookieStore.KEY_IGNEOUS -> {
                    igneous = it.value
                    igneousExpire = it.expiresAt
                }
            }
        }
        val cookie = buildString {
            append(EhCookieStore.KEY_IPB_MEMBER_ID).append(": ").append(ipbMemberId)
            append('\n').append(EhCookieStore.KEY_IPB_PASS_HASH).append(": ").append(ipbPassHash)
            igneous?.let { append('\n').append(EhCookieStore.KEY_IGNEOUS).append(": ").append(it) }
        }
        accountCookie = cookie
        var message = app.getString(R.string.settings_eh_account_identity_cookies, cookie)
        if (igneousExpire > 0 && igneousExpire != ReadableTime.MAX_VALUE_MILLIS) {
            message += "\n\n" + app.getString(R.string.settings_eh_account_igneous_expire) +
                ReadableTime.getShortTime(igneousExpire)
        }
        accountMessage = message
    }

    private fun Throwable.readableMessage(): String = message ?: app.getString(R.string.error_something_wrong_happened)
}
