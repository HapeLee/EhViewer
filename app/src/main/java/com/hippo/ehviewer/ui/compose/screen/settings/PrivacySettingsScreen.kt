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

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings

/**
 * 隐私分类页（原 `privacy_settings.xml` + `PrivacyFragment`）。
 *
 * 手势锁那一行的摘要跟着 `Settings.security` 变：老实现是在 `onStart()` 里读一次，
 * 现在从手势锁页返回（保存或清除图案）会立刻反映出来。
 */
@Composable
fun PrivacySettingsScreen(
    patternSet: Boolean,
    onBack: () -> Unit,
    onOpenPattern: () -> Unit,
    onClearSearchHistory: () -> Unit,
) {
    SettingsScaffold(titleRes = R.string.settings_privacy, onBack = onBack) {
        PreferenceRow(
            title = stringResource(R.string.settings_privacy_pattern_protection_title),
            summary = stringResource(
                if (patternSet) {
                    R.string.settings_privacy_pattern_protection_set
                } else {
                    R.string.settings_privacy_pattern_protection_not_set
                },
            ),
            onClick = onOpenPattern,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_SEC_SECURITY,
            default = false,
            titleRes = R.string.settings_privacy_secure,
            summaryRes = R.string.settings_privacy_secure_summary,
        )
        PreferenceRow(
            title = stringResource(R.string.settings_privacy_clear_search_history),
            summary = stringResource(R.string.settings_privacy_clear_search_history_summary),
            onClick = onClearSearchHistory,
        )
    }
}
