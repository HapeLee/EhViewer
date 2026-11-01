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
package com.hippo.ehviewer.ui.compose.screen.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.compose.nav.SettingsPage

/**
 * 设置首页（原 `settings_headers.xml` + `SettingsFragment`）。
 *
 * 拆分口径是「一个文件 = 一个可导航目的地」（§11.1）：这一页只做分类罗列与分发，
 * 具体配置项在各自的分类页里纵向分组。
 *
 * 还没迁到 Compose 的分类页交给 [onOpenPage]——过渡期由宿主打开老的
 * `SettingsActivity`；`About` 已经是 Compose 路由，走 [onOpenAbout]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.v_arrow_left_dark_x24),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsEntry(R.drawable.v_sad_panda_primary_x24, R.string.settings_eh) {
                onOpenPage(SettingsPage.EH)
            }
            SettingsEntry(R.drawable.v_book_open_primary_x24, R.string.settings_read) {
                onOpenPage(SettingsPage.READ)
            }
            SettingsEntry(R.drawable.v_download_primary_x24, R.string.settings_download) {
                onOpenPage(SettingsPage.DOWNLOAD)
            }
            SettingsEntry(R.drawable.v_sec_primary_x24, R.string.settings_privacy) {
                onOpenPage(SettingsPage.PRIVACY)
            }
            SettingsEntry(R.drawable.v_adb_primary_x24, R.string.settings_advanced) {
                onOpenPage(SettingsPage.ADVANCED)
            }
            SettingsEntry(
                iconRes = R.drawable.v_info_primary_x24,
                titleRes = R.string.settings_about,
                onClick = onOpenAbout,
                showDivider = false,
            )
        }
    }
}

/** 分类入口行：图标 + 标题，与原 `Preference` 的一行等价（无摘要、无右侧箭头） */
@Composable
private fun SettingsEntry(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
