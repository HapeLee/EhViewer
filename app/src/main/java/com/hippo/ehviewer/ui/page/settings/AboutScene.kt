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

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.settings.AboutScreen

/**
 * 关于页。
 *
 * 链接一律外跳浏览器（与原 `UrlPreference.onClick` 的
 * `UrlOpener.openUrl(context, url, true)` 一致），不在返回栈里再开 WebView。
 */
@Composable
fun AboutScene() {
    val context = LocalContext.current
    val navigator = ehNavigator()

    AboutScreen(
        onBack = navigator::pop,
        onOpenUrl = { url -> UrlOpener.openUrl(context, url, true) },
    )
}
