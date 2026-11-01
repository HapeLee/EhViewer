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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.hippo.ehviewer.Settings

/**
 * Material 3 Expressive 主题。
 *
 * 说明：
 * - 明暗由 [isSystemInDarkTheme] 决定，而 app 的明暗已经通过
 *   `AppCompatDelegate.setDefaultNightMode(Settings.theme)` 写进资源配置，
 *   因此这里不需要再读一次 `Settings.theme`。
 * - minSdk = 31，Monet 动态取色一定能用，直接走 `dynamicXXXColorScheme`，
 *   不再保留低版本种子色回退分支。
 * - 纯黑（AMOLED）沿用 `Settings.blackDarkTheme`，只覆盖 surface 一族色值。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EhTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    // SharedPreferences 读取不放进组合阶段反复执行
    val isAmoled = remember { isDark && Settings.blackDarkTheme }
    MaterialExpressiveTheme(colorScheme = ehColorScheme(isDark, isAmoled), content = content)
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
    return remember(context, dark, amoled) {
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (amoled) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF101010),
                surfaceContainer = Color(0xFF161616),
                surfaceContainerHigh = Color(0xFF202020),
                surfaceContainerHighest = Color(0xFF2C2C2C),
            )
        } else {
            base
        }
    }
}
