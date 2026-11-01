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
package com.hippo.ehviewer.ui.reader

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.compose.theme.ehColorScheme

/**
 * 阅读器自带的配色覆盖，对应旧 `read_theme`。
 *
 * 旧 `GalleryActivity` 的做法是在 `attachBaseContext` 里改 `localNightMode`，改完还得
 * `recreate()` 才能让新资源生效——换成 NavHost 单宿主后，recreate 会把整棵导航栈重建，
 * 代价完全不成比例。这里只要在阅读器子树里换一套 `ColorScheme` 就够了，效果一样，
 * 而且不需要重建 Activity。
 *
 * [READ_THEME_FOLLOW_APP] 直接透传，沿用外层 [com.hippo.ehviewer.ui.compose.theme.EhTheme]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderTheme(readTheme: Int, content: @Composable () -> Unit) {
    if (readTheme != READ_THEME_DARK && readTheme != READ_THEME_LIGHT) {
        content()
        return
    }
    val dark = readTheme == READ_THEME_DARK
    // 纯黑只对深色有意义，浅色阅读主题下不叠 AMOLED
    val amoled = remember(dark) { dark && Settings.blackDarkTheme }
    MaterialExpressiveTheme(colorScheme = ehColorScheme(dark, amoled), content = content)
}
