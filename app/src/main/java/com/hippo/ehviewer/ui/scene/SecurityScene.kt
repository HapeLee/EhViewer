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

import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.CHECK_STEP_SECURITY
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.nav.nextCheckRoute
import com.hippo.ehviewer.ui.compose.nav.targetOf
import com.hippo.ehviewer.ui.compose.screen.SecurityScreen
import com.hippo.widget.lockpattern.LockPatternUtils
import com.hippo.widget.lockpattern.LockPatternView
import com.hippo.yorozuya.ObjectUtils
import java.util.concurrent.Executors

/**
 * 手势锁页。
 *
 * 迁移点：
 * - 布局容器换成 Compose，[LockPatternView] 本身原样保留（图案绘制/手势解析不动）
 * - 指纹提示、重试次数、成功后的 `CHECK_STEP_SECURITY` 流程与原来一致
 */
class SecurityScene : EhComposeScene() {

    private var patternView: LockPatternView? = null
    private var canAuthenticate = false
    private var retryTimes = MAX_RETRY_TIMES
    private var promptInfo: BiometricPrompt.PromptInfo? = null
    private var biometricPrompt: BiometricPrompt? = null

    override fun needShowLeftDrawer(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        retryTimes = savedInstanceState?.getInt(KEY_RETRY_TIMES) ?: MAX_RETRY_TIMES
        canAuthenticate = Settings.enableFingerprint &&
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        biometricPrompt = BiometricPrompt(
            this,
            Executors.newSingleThreadExecutor(),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    unlock()
                }
            },
        )
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setNegativeButtonText(getString(android.R.string.cancel))
            .setConfirmationRequired(false)
            .build()
    }

    override fun onResume() {
        super.onResume()
        if (canAuthenticate) {
            startBiometricPrompt()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_RETRY_TIMES, retryTimes)
    }

    override fun onDestroyView() {
        patternView = null
        super.onDestroyView()
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.Security> {
            SecurityScreen(
                canAuthenticate = canAuthenticate,
                onPatternDetected = ::onPatternDetected,
                onViewCreated = { patternView = it },
                onBiometricClick = ::startBiometricPrompt,
            )
        }
    }

    private fun startBiometricPrompt() {
        biometricPrompt?.authenticate(promptInfo ?: return)
    }

    private fun onPatternDetected(pattern: List<LockPatternView.Cell>) {
        val view = patternView ?: return
        if (mainActivity == null) return
        val enteredPattern = LockPatternUtils.patternToString(pattern)
        if (ObjectUtils.equal(enteredPattern, Settings.security)) {
            unlock()
        } else {
            view.setDisplayMode(LockPatternView.DisplayMode.Wrong)
            retryTimes--
            if (retryTimes <= 0) {
                finish()
            }
        }
    }

    private fun unlock() {
        ehNavigator().replaceTop(nextCheckRoute(CHECK_STEP_SECURITY, targetOf(ehRoute)))
    }

    companion object {
        private const val KEY_RETRY_TIMES = "retry_times"
        private const val MAX_RETRY_TIMES = 5
    }
}
