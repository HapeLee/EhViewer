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
package com.hippo.ehviewer.ui.compose.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.hippo.ehviewer.Settings

/**
 * Material 3 Expressive 主题。
 *
 * 说明：
 * - 明暗由 [isSystemInDarkTheme] 决定，而 `Settings.theme` 是在
 *   `EhActivity.attachBaseContext` 里写进资源配置的（不是
 *   `AppCompatDelegate.setDefaultNightMode`，那个对 `FragmentActivity` 不生效），
 *   因此这里不需要再读一次 `Settings.theme`。
 * - minSdk = 31，Monet 动态取色一定能用，直接走 `dynamicXXXColorScheme`，
 *   不再保留低版本种子色回退分支。
 * - 纯黑（AMOLED）沿用 `Settings.blackDarkTheme`，覆盖 surface 一族色值。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EhTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // SharedPreferences 读取不放进组合阶段反复执行
    val isAmoled = remember(darkTheme) { darkTheme && Settings.blackDarkTheme }
    val colorScheme = ehColorScheme(darkTheme, isAmoled)
    EhSystemBars(darkTheme = darkTheme, colorScheme = colorScheme)
    MaterialExpressiveTheme(colorScheme = colorScheme) {
        // material3 1.5 的 MaterialTheme 只提供 ColorScheme / MotionScheme / Shapes / Typography，
        // **不再提供 LocalContentColor**（1.3 及以前会提供 contentColorFor(background)）。
        // 它的默认值是 Color.Black，所以任何没被 Surface 之类组件包住的 Text / Icon 都会掉回黑色。
        // 这里补回 Material 语义上的「窗口正文色」，等价于旧主题的行为。
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            content = content,
        )
    }
}

/**
 * 把当前色板同步到窗口：状态栏 / 导航栏的图标明暗与底色。
 *
 * 放在组合里而不是 `Activity.onCreate`，是因为 `EhTheme` 有两个调用方：宿主用
 * `Settings.theme`，阅读器则把明暗钉死成页面底色。只有跟着实际生效的
 * [colorScheme] 走，黑底阅读器（系统是浅色）上的系统栏图标才不会黑到看不见。
 */
@Composable
private fun EhSystemBars(darkTheme: Boolean, colorScheme: ColorScheme) {
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(darkTheme, colorScheme, view) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.Transparent.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = colorScheme.background.copy(alpha = 0.88f).toArgb()
        window.isNavigationBarContrastEnforced = false
    }
}

/** 从任意 Context 往上找宿主 Activity（窗口操作要用） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 按「明暗 + 纯黑」算一套色板。
 *
 * 单独抽出来是为了让阅读器的 `read_theme` 覆盖（它要把明暗钉死成深/浅）复用同一套
 * 取色逻辑，而不是抄一份会漂移的副本。
 */
@Composable
fun ehColorScheme(dark: Boolean, amoled: Boolean): ColorScheme {
    val context = LocalContext.current
    return remember(context, dark, amoled) { ehColorScheme(context, dark, amoled) }
}

/**
 * 非组合版本：给组合之外（比如算窗口背景）复用同一套取色。
 *
 * AMOLED 只改「面」不改「字」：除 `background` / `surface` 两族之外，
 * `surfaceVariant`（分割线、次级容器）、`surfaceDim` / `surfaceBright`（M3E 的容器分层）
 * 和 `surfaceTint`（高程着色）也要一起压黑，否则纯黑页面上仍会浮出一块块深灰。
 */
fun ehColorScheme(context: Context, dark: Boolean, amoled: Boolean): ColorScheme {
    val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    if (!amoled) return base
    return base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF2C2C2C),
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF161616),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF101010),
        surfaceContainer = Color(0xFF161616),
        surfaceContainerHigh = Color(0xFF202020),
        surfaceContainerHighest = Color(0xFF2C2C2C),
        // 纯黑上没有「按高程加一点主色」的余地，置黑等于关掉 tonal elevation
        surfaceTint = Color.Black,
    )
}

/** 组合之外取「当前这套色板」：明暗跟资源配置走，纯黑跟 `Settings` 走 */
private fun currentColorScheme(context: Context): ColorScheme {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES != 0
    return ehColorScheme(context, dark, dark && Settings.blackDarkTheme)
}

/**
 * 窗口背景色：没给自己铺底的页面（下载页就是个裸 `Column`）会直接透出窗口色。
 * XML 主题删掉之后 `android:windowBackground` 只剩框架默认值，所以在 `onCreate`
 * 里按当前色板刷一次窗口。
 */
fun ehWindowBackground(context: Context): Int = currentColorScheme(context).background.toArgb()

/** Custom Tabs 的工具栏色：原来是 XML 主题里的 `R.attr.toolbarColor`，现在直接取 surface */
fun ehToolbarColor(context: Context): Int = currentColorScheme(context).surface.toArgb()
