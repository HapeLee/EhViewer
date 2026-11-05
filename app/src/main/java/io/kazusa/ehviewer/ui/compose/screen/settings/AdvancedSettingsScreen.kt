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

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings

/**
 * 高级分类页（原 `advanced_settings.xml` + `AdvancedFragment`）。
 *
 * 代理与 UA 两个 `DialogPreference` 换成 Compose 对话框；导出、导入、打包日志三类
 * 「先选文件再干活」的动作由场景提供 SAF 回调，页面只负责入口与置灰。
 */
@Composable
fun AdvancedSettingsScreen(
    proxySummary: String,
    userAgent: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenProxy: () -> Unit,
    onOpenUserAgent: () -> Unit,
    onDumpLogcat: () -> Unit,
    onBackupFavorite: () -> Unit,
    onExportData: () -> Unit,
    onImportData: () -> Unit,
    onOpenByDefault: () -> Unit,
    onAppLanguageChanged: (String) -> Unit,
) {
    SettingsScaffold(titleRes = R.string.settings_advanced, onBack = onBack) {
        // 日志与诊断
        SettingsGroup {
            SwitchPreferenceRow(
                key = Settings.KEY_SAVE_PARSE_ERROR_BODY,
                default = true,
                titleRes = R.string.settings_advanced_save_parse_error_body,
                summaryRes = R.string.settings_advanced_save_parse_error_body_summary,
            )
            SwitchPreferenceRow(
                key = Settings.KEY_SAVE_CRASH_LOG,
                default = true,
                titleRes = R.string.settings_advanced_save_crash_log,
                summaryRes = R.string.settings_advanced_save_crash_log_summary,
            )
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_dump_logcat),
                summary = stringResource(R.string.settings_advanced_dump_logcat_summary),
                enabled = !busy,
                onClick = onDumpLogcat,
            )
        }

        // 缓存与语言
        SettingsGroup {
            ListPreferenceRow(
                key = Settings.KEY_READ_CACHE_SIZE,
                default = 320,
                titleRes = R.string.settings_advanced_read_cache_size,
                entriesRes = R.array.read_cache_size_entries,
                valuesRes = R.array.read_cache_size_entry_values,
            )
            StringListPreferenceRow(
                key = Settings.KEY_APP_LANGUAGE,
                default = "system",
                titleRes = R.string.settings_advanced_app_language_title,
                entriesRes = R.array.app_language_entries,
                valuesRes = R.array.app_language_entry_values,
                onChange = onAppLanguageChanged,
            )
        }

        // 网络
        SettingsGroup {
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_proxy),
                summary = proxySummary,
                onClick = onOpenProxy,
            )
            SwitchPreferenceRow(
                key = Settings.KEY_DOH,
                default = true,
                titleRes = R.string.settings_advanced_dns_over_http_title,
                summaryRes = R.string.settings_advanced_dns_over_http_summary,
            )
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_user_agent_title),
                summary = userAgent,
                onClick = onOpenUserAgent,
            )
        }

        // 备份与导入
        SettingsGroup {
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_backup_favorite),
                summary = stringResource(R.string.settings_advanced_backup_favorite_summary),
                enabled = !busy,
                onClick = onBackupFavorite,
            )
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_export_data),
                summary = stringResource(R.string.settings_advanced_export_data_summary),
                enabled = !busy,
                onClick = onExportData,
            )
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_import_data),
                summary = stringResource(R.string.settings_advanced_import_data_summary),
                enabled = !busy,
                onClick = onImportData,
            )
            PreferenceRow(
                title = stringResource(R.string.settings_advanced_open_by_default),
                onClick = onOpenByDefault,
            )
        }
    }
}
