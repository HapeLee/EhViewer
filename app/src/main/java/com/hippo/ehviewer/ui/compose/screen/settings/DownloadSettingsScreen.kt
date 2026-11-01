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

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings

/**
 * 下载分类页（原 `download_settings.xml` + `DownloadFragment`）。
 *
 * 四项 `SimpleMenuPreference` 的摘要是「前缀 + 当前条目」（`setSummaryProvider`），
 * 这里换成 [ListPreferenceRow] 的 `summaryFormat`；恢复与整理两个动作耗时较长，
 * 运行期间置灰，避免连点。
 */
@Composable
fun DownloadSettingsScreen(
    downloadLocationUri: String?,
    running: Boolean,
    onBack: () -> Unit,
    onOpenLocation: () -> Unit,
    onMediaScanChanged: (Boolean) -> Unit,
    onRestoreDownload: () -> Unit,
    onCleanRedundancy: () -> Unit,
) {
    val resources = LocalResources.current

    SettingsScaffold(titleRes = R.string.settings_download, onBack = onBack) {
        PreferenceRow(
            title = stringResource(R.string.settings_download_download_location),
            summary = downloadLocationUri
                ?: stringResource(R.string.settings_download_invalid_download_location),
            onClick = onOpenLocation,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_MEDIA_SCAN,
            default = false,
            titleRes = R.string.settings_download_media_scan,
            summaryOn = stringResource(R.string.settings_download_media_scan_summary_on),
            summaryOff = stringResource(R.string.settings_download_media_scan_summary_off),
            onChange = onMediaScanChanged,
        )
        ListPreferenceRow(
            key = Settings.KEY_MULTI_THREAD_DOWNLOAD,
            default = 3,
            titleRes = R.string.settings_download_concurrency,
            entriesRes = R.array.multi_thread_download_entries,
            valuesRes = R.array.multi_thread_download_entry_values,
            summaryFormat = { entry ->
                resources.getString(R.string.settings_download_concurrency_summary, entry)
            },
        )
        ListPreferenceRow(
            key = Settings.KEY_DOWNLOAD_DELAY,
            default = 1000,
            titleRes = R.string.settings_download_download_delay,
            entriesRes = R.array.download_delay_entries,
            valuesRes = R.array.download_delay_entry_values,
            summaryFormat = { entry ->
                resources.getString(R.string.settings_download_download_delay_summary, entry)
            },
        )
        SliderPreferenceRow(
            key = Settings.KEY_DOWNLOAD_TIMEOUT,
            default = 60,
            titleRes = R.string.settings_download_download_timeout,
            range = 20..120,
        )
        ListPreferenceRow(
            key = Settings.KEY_PRELOAD_IMAGE,
            default = 5,
            titleRes = R.string.settings_download_preload_image,
            entriesRes = R.array.preload_image_entries,
            valuesRes = R.array.preload_image_entry_values,
            summaryFormat = { entry ->
                resources.getString(R.string.settings_download_preload_image_summary, entry)
            },
        )
        ListPreferenceRow(
            key = Settings.KEY_DOWNLOAD_ORIGIN_IMAGE,
            default = 0,
            titleRes = R.string.settings_download_download_origin_image,
            entriesRes = R.array.download_origin_image_entries,
            valuesRes = R.array.download_origin_image_entry_values,
            summaryFormat = { entry ->
                resources.getString(R.string.settings_download_download_origin_image_summary, entry)
            },
        )
        PreferenceRow(
            title = stringResource(R.string.settings_download_restore_download_items),
            summary = stringResource(R.string.settings_download_restore_download_items_summary),
            enabled = !running,
            onClick = onRestoreDownload,
        )
        PreferenceRow(
            title = stringResource(R.string.settings_download_clean_redundancy),
            summary = stringResource(R.string.settings_download_clean_redundancy_summary),
            enabled = !running,
            onClick = onCleanRedundancy,
        )
    }
}

/**
 * 自定义下载目录时的确认框（原 `DownloadFragment.onPreferenceClick` 里的
 * `AlertDialog`）：换目录走 SAF，恢复默认直接改回 `AppConfig.getDefaultDownloadDir()`。
 */
@Composable
fun DownloadLocationDialog(
    location: String,
    onPickNew: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_download_download_location)) },
        text = { Text(text = location) },
        confirmButton = {
            Row {
                TextButton(onClick = onPickNew) {
                    Text(text = stringResource(R.string.settings_download_pick_new_location))
                }
                TextButton(onClick = onReset) {
                    Text(text = stringResource(R.string.settings_download_reset_location))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
