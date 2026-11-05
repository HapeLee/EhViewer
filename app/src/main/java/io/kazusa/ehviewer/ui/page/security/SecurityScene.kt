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

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.support.widget.lockpattern.LockPatternUtils
import io.kazusa.ehviewer.support.widget.lockpattern.LockPatternView
import io.kazusa.ehviewer.ui.compose.nav.CHECK_STEP_SECURITY
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.nav.nextCheckRoute
import io.kazusa.ehviewer.ui.compose.nav.targetOf
import io.kazusa.ehviewer.ui.compose.screen.SecurityScreen
import io.kazusa.ehviewer.ui.host.OnHostResume
import java.util.concurrent.Executors

private const val MAX_RETRY_TIMES = 5

/**
 * 手势锁页（守卫链的第一站）。
 *
 * P7-c 出壳：不再是 `EhComposeScene` 的子类，也不再套一层内部 `NavDisplay`；
 * 整个页面就是挂在宿主返回栈上的一个 @Composable。
 * 图案绘制与手势识别仍由 [LockPatternView] 负责，指纹提示时机与重试上限保持原样。
 */
@Composable
fun SecurityScene(route: EhRoute.Security) {
    val context = LocalContext.current
    // 读资源用 LocalResources：LocalContext.current 不随 Configuration 变化失效
    val resources = LocalResources.current
    val navigator = ehNavigator()
    var patternView by remember { mutableStateOf<LockPatternView?>(null) }
    var retryTimes by rememberSaveable { mutableIntStateOf(MAX_RETRY_TIMES) }
    val appName = stringResource(R.string.app_name)

    val canAuthenticate = remember {
        Settings.enableFingerprint &&
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    val unlock = remember(navigator, route) {
        { navigator.replaceTop(nextCheckRoute(CHECK_STEP_SECURITY, targetOf(route))) }
    }
    val promptInfo = remember(resources, appName) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(appName)
            .setNegativeButtonText(resources.getString(android.R.string.cancel))
            .setConfirmationRequired(false)
            .build()
    }
    val prompt = remember(context, unlock) {
        BiometricPrompt(
            context as FragmentActivity,
            Executors.newSingleThreadExecutor(),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    unlock()
                }
            },
        )
    }
    val startPrompt = remember(prompt, promptInfo, canAuthenticate) {
        { if (canAuthenticate) prompt.authenticate(promptInfo) }
    }

    // 进入前台自动拉起指纹（对应旧的 `onResume()`）
    OnHostResume(startPrompt)

    SecurityScreen(
        canAuthenticate = canAuthenticate,
        onPatternDetected = { pattern ->
            val view = patternView ?: return@SecurityScreen
            if (LockPatternUtils.patternToString(pattern) == Settings.security) {
                unlock()
            } else {
                view.setDisplayMode(LockPatternView.DisplayMode.Wrong)
                retryTimes--
                if (retryTimes <= 0) {
                    navigator.pop()
                }
            }
        },
        onViewCreated = { patternView = it },
        onBiometricClick = startPrompt,
    )
}
