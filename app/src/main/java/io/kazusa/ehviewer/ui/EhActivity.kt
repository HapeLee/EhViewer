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
package io.kazusa.ehviewer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import io.kazusa.ehviewer.EhApplication
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.ui.compose.theme.ehWindowBackground

/**
 * 宿主 Activity 的基类。
 *
 * 主题这一层已经整个交给 Compose（`EhTheme`）：页面颜色一律由动态取色的 `ColorScheme`
 * 决定，`themes_override.xml` / `styles.xml` 也都删了，只剩 `values/themes.xml` 里那个
 * 什么都不管的 `Theme.EhViewer`（AppCompat DayNight + NoActionBar，给窗口、Dialog 和
 * 老矢量图的 `?attr` 兜底，不再依赖 MDC）。于是 `AppCompatActivity` 也不再需要
 * （跟 Matter 一致）：
 * - 旧的 `LayoutInflaterFactory(delegate)` + `WindowInsetsHelper` 是给 XML 布局做
 *   fitsSystemWindows 的，`res/layout` 清空后本来也没活干，一并去掉；
 * - AMOLED（纯黑）原来靠 `ThemeOverlay.Black` 叠加，现在在 `ehColorScheme` 里覆盖；
 * - 最后一批吃 XML 主题色的 View（`MaterialDatePicker`）已经换成 Compose 的
 *   `GoToDateDialog`，`DynamicColors` 也随之移除。
 *
 * 代价是 `AppCompatDelegate.setDefaultNightMode` 对 [FragmentActivity] **不生效**
 * （它只派发给注册过的 AppCompat delegate），所以 `Settings.theme` 得自己写进配置，
 * 见 [attachBaseContext]。
 *
 * 仍是 [FragmentActivity] 而不是 `ComponentActivity`：`BiometricPrompt`
 * （`SecurityScene` 的指纹解锁）与 `EhRequest` 都要求 `FragmentActivity`。
 */
abstract class EhActivity : FragmentActivity() {
    /**
     * 把 `Settings.theme`（沿用 AppCompat 的 `MODE_NIGHT_*` 口径）写进本 Activity 的配置。
     *
     * 这是「跟随系统 / 浅色 / 深色」三个选项唯一的生效点：`EhTheme` 读的是
     * `isSystemInDarkTheme()`，而它只看 `LocalConfiguration.uiMode`。少了这一步，
     * 无论怎么切设置、怎么 `recreate()`，`uiMode` 都还是系统值。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withNightMode(Settings.theme))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `EhTheme` 起来之后会按实际色板再同步一次系统栏（阅读器会把明暗钉死）；
        // 这里只是为了第一帧之前的兜底，明暗取自已按 `Settings.theme` 覆写过的配置
        val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES != 0
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.post {
            window.navigationBarColor = ehWindowBackground(this@EhActivity) and 0x00ffffff or -0x20000000
            window.isNavigationBarContrastEnforced = false
        }
        (application as EhApplication).registerActivity(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as EhApplication).unregisterActivity(this)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.enabledSecurity) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { Settings.putNotificationRequired() }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun checkAndRequestNotificationPermission() {
        if (Settings.notificationRequired || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * 按 `Settings.theme` 覆写这份配置的 `uiMode`。
 *
 * - `MODE_NIGHT_NO` / `MODE_NIGHT_YES` 把昼夜位钉死；
 * - `MODE_NIGHT_FOLLOW_SYSTEM`（以及其它值）原样返回，交给系统配置。
 *
 * `Configuration` 要基于 `newBase` 的配置拷贝：`attachBaseContext` 阶段这个 Context
 * 自己还没有可用的 `resources`。
 */
private fun Context.withNightMode(mode: Int): Context {
    val nightMode = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
        AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
        else -> return this
    }
    val configuration = Configuration(resources.configuration)
    configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    return createConfigurationContext(configuration)
}
