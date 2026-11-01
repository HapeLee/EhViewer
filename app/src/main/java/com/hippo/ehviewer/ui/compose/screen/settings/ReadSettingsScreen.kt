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
import androidx.compose.runtime.collectAsState
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings

/**
 * 阅读分类页（原 `read_settings.xml` + `ReadFragment`）。
 *
 * 两项 `android:dependency="volume_page"` / `"custom_screen_lightness"` 的老写法
 * 换成 `enabled`：条件项变灰而不是消失，用户能看到「先打开开关」。
 */
@Composable
fun ReadSettingsScreen(onBack: () -> Unit) {
    val volumePage = Settings.valueFlow(Settings.KEY_VOLUME_PAGE, false)
        .collectAsState(initial = Settings.volumePage).value
    val customLightness = Settings.valueFlow(Settings.KEY_CUSTOM_SCREEN_LIGHTNESS, false)
        .collectAsState(initial = Settings.customScreenLightness).value

    SettingsScaffold(titleRes = R.string.settings_read, onBack = onBack) {
        ListPreferenceRow(
            key = Settings.KEY_SCREEN_ROTATION,
            default = 0,
            titleRes = R.string.settings_read_screen_rotation,
            entriesRes = R.array.screen_rotation_entries,
            valuesRes = R.array.screen_rotation_entry_values,
        )
        ListPreferenceRow(
            key = Settings.KEY_READING_DIRECTION,
            default = 1,
            titleRes = R.string.settings_read_reading_direction,
            entriesRes = R.array.reading_direction_entries,
            valuesRes = R.array.reading_direction_entry_values,
        )
        ListPreferenceRow(
            key = Settings.KEY_PAGE_SCALING,
            default = 3,
            titleRes = R.string.settings_read_page_scaling,
            entriesRes = R.array.page_scaling_entries,
            valuesRes = R.array.page_scaling_entry_values,
        )
        ListPreferenceRow(
            key = Settings.KEY_START_POSITION,
            default = 1,
            titleRes = R.string.settings_read_start_position,
            entriesRes = R.array.start_position_entries,
            valuesRes = R.array.start_position_values,
        )
        ListPreferenceRow(
            key = Settings.KEY_READ_THEME,
            default = 1,
            titleRes = R.string.settings_read_theme,
            entriesRes = R.array.read_theme_entries,
            valuesRes = R.array.read_theme_entry_values,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_KEEP_SCREEN_ON,
            default = false,
            titleRes = R.string.settings_read_keep_screen_on,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_SHOW_PAGE_INTERVAL,
            default = false,
            titleRes = R.string.settings_read_show_page_interval,
        )
        SliderPreferenceRow(
            key = Settings.KEY_TURN_PAGE_INTERVAL,
            default = 5,
            titleRes = R.string.settings_read_turn_page_interval,
            range = 1..30,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_VOLUME_PAGE,
            default = false,
            titleRes = R.string.settings_read_volume_page,
        )
        SliderPreferenceRow(
            key = Settings.KEY_VOLUME_PAGE_INTERVAL,
            default = 1,
            titleRes = R.string.settings_read_volume_page_interval,
            range = 0..14,
            enabled = volumePage,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_REVERSE_VOLUME_PAGE,
            default = false,
            titleRes = R.string.settings_read_reverse_volume,
            enabled = volumePage,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_READING_FULLSCREEN,
            default = true,
            titleRes = R.string.settings_read_reading_fullscreen,
        )
        SwitchPreferenceRow(
            key = Settings.KEY_CUSTOM_SCREEN_LIGHTNESS,
            default = false,
            titleRes = R.string.settings_read_custom_screen_lightness,
        )
        SliderPreferenceRow(
            key = Settings.KEY_SCREEN_LIGHTNESS,
            default = 50,
            titleRes = R.string.settings_read_screen_lightness,
            range = 0..200,
            enabled = customLightness,
        )
    }
}
