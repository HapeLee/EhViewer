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
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.asMutableState
import io.kazusa.ehviewer.tachiyomi.ui.reader.setting.OrientationType
import io.kazusa.ehviewer.tachiyomi.ui.reader.setting.ReadingModeType

/**
 * 阅读分类页。
 *
 * 阅读器已经自带三页设置面板（EHz 那一套），这里只放「进阅读器之前就想调」的几项：
 * 模式、方向、主题、全屏、常亮、音量键。每条都直接读写阅读器真正用的 key，
 * 不再经过旧的 `KEY_READING_DIRECTION` 那一批（编码不同，留着只会写出无人读取的值）。
 *
 * 行用的是 [ListFieldRow] / [SwitchFieldRow]（绑 `MutableState` 的版本），
 * 而不是阅读器面板里的 `SpinnerChoice` / `SwitchChoice`——后者的标题是次级色、
 * 也没有摘要行，摆在分组卡里会和别的分类页对不上。
 */
@Composable
fun ReadSettingsScreen(onBack: () -> Unit) {
    SettingsScaffold(titleRes = R.string.settings_read, onBack = onBack) {
        SettingsGroup {
            ListFieldRow(
                title = stringResource(R.string.pref_category_reading_mode),
                entries = stringArrayResource(R.array.viewers_selector).toList(),
                values = ReadingModeType.entries.map { it.prefValue },
                field = Settings.readingMode.asMutableState(),
            )
            ListFieldRow(
                title = stringResource(R.string.rotation_type),
                entries = stringArrayResource(R.array.rotation_type).toList(),
                values = OrientationType.entries.map { it.prefValue },
                field = Settings.orientationMode.asMutableState(),
            )
            ListFieldRow(
                title = stringResource(R.string.pref_reader_theme),
                entries = stringArrayResource(R.array.reader_themes).toList(),
                values = integerArrayResource(R.array.reader_themes_values).toList(),
                field = Settings.readerTheme.asMutableState(),
            )
        }

        SettingsGroup {
            SwitchFieldRow(
                title = stringResource(R.string.pref_fullscreen),
                field = Settings.fullscreen.asMutableState(),
            )
            SwitchFieldRow(
                title = stringResource(R.string.pref_keep_screen_on),
                field = Settings.keepScreenOnPref.asMutableState(),
            )
        }

        SettingsGroup {
            val volume = Settings.readWithVolumeKeys.asMutableState()
            SwitchFieldRow(
                title = stringResource(R.string.settings_read_volume_page),
                field = volume,
            )
            if (volume.value) {
                SwitchFieldRow(
                    title = stringResource(R.string.settings_read_reverse_volume),
                    field = Settings.readWithVolumeKeysInverted.asMutableState(),
                )
            }
        }
    }
}
