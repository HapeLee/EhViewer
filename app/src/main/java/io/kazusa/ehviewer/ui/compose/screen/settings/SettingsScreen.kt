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
package io.kazusa.ehviewer.ui.compose.screen.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.ui.compose.nav.SettingsPage

/**
 * 设置首页（原 `settings_headers.xml` + `SettingsFragment`）。
 *
 * 拆分口径是「一个文件 = 一个可导航目的地」（§11.1）：这一页只做分类罗列与分发，
 * 具体配置项在各自的分类页里纵向分组。
 *
 * 分类页统一交给 [onOpenPage]（宿主压一条 `SettingsCategory` 路由），
 * `About` 走 [onOpenAbout]。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenAbout: () -> Unit,
) {
    SettingsScaffold(titleRes = R.string.settings, onBack = onBack) {
        SettingsGroup {
            // 只有 EH 分类还留着矢量图（低多边形熊猫是自家插画），其余用 Material Icons
            SettingsEntry(painterResource(R.drawable.v_sad_panda_primary_x24), R.string.settings_eh) {
                onOpenPage(SettingsPage.EH)
            }
            SettingsEntry(rememberVectorPainter(Icons.AutoMirrored.Filled.MenuBook), R.string.settings_read) {
                onOpenPage(SettingsPage.READ)
            }
            SettingsEntry(rememberVectorPainter(Icons.Filled.Download), R.string.settings_download) {
                onOpenPage(SettingsPage.DOWNLOAD)
            }
            SettingsEntry(rememberVectorPainter(Icons.Filled.Security), R.string.settings_privacy) {
                onOpenPage(SettingsPage.PRIVACY)
            }
            SettingsEntry(rememberVectorPainter(Icons.Filled.Build), R.string.settings_advanced) {
                onOpenPage(SettingsPage.ADVANCED)
            }
            SettingsEntry(rememberVectorPainter(Icons.Filled.Info), R.string.settings_about, onClick = onOpenAbout)
        }
    }
}

/** 分类入口行：图标 + 标题，与原 `Preference` 的一行等价（无摘要、无右侧箭头） */
@Composable
private fun SettingsEntry(
    icon: Painter,
    @StringRes titleRes: Int,
    onClick: () -> Unit,
) {
    PreferenceRow(
        title = stringResource(titleRes),
        icon = icon,
        onClick = onClick,
    )
}
