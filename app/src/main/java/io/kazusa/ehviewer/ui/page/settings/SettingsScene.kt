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
package io.kazusa.ehviewer.ui.page.settings

import androidx.compose.runtime.Composable
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.screen.settings.SettingsScreen

/**
 * 设置首页。
 *
 * 首页本身没有任何配置项，所以这里不建 ViewModel——它只做分发：分类页一律压
 * [EhRoute.SettingsCategory]，由 [SettingsCategoryScene] 就地渲染。
 */
@Composable
fun SettingsScene() {
    val navigator = ehNavigator()

    SettingsScreen(
        onBack = navigator::pop,
        onOpenPage = { page -> navigator.push(EhRoute.SettingsCategory(page)) },
        onOpenAbout = { navigator.push(EhRoute.About) },
    )
}
