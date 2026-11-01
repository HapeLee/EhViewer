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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import kotlin.math.roundToInt

/**
 * 阅读设置面板。
 *
 * 照 legado 的第 6 点：设置是**一整个 [ReaderSettings]**，改哪项都走同一个
 * [onSettings] 回调，而不是 N 个 `setBrightness` / `setScaleType`。
 *
 * `read_theme` 也在里面：它不再需要 recreate Activity，改完直接换阅读器子树的色板
 * （见 [ReaderTheme]），所以放在这里按普通选项处理。
 */
@Composable
fun ReaderSettingsDialog(
    settings: ReaderSettings,
    onSettings: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    ChoiceRow(
                        label = stringResource(R.string.settings_read_reading_direction),
                        options = listOf(
                            stringResource(R.string.settings_read_reading_direction_left_to_right),
                            stringResource(R.string.settings_read_reading_direction_right_to_Left),
                            stringResource(R.string.settings_read_reading_direction_top_to_bottom),
                        ),
                        selected = when {
                            settings.vertical -> 2
                            settings.reverseLayout -> 1
                            else -> 0
                        },
                        onSelect = {
                            onSettings(settings.copy(vertical = it == 2, reverseLayout = it == 1))
                        },
                    )
                }
                item {
                    ChoiceRow(
                        label = stringResource(R.string.settings_read_page_scaling),
                        options = listOf(
                            stringResource(R.string.settings_read_page_scaling_actual_size),
                            stringResource(R.string.settings_read_page_scaling_fit_to_width),
                            stringResource(R.string.settings_read_page_scaling_fit_to_height),
                            stringResource(R.string.settings_read_page_scaling_fit_to_screen),
                            stringResource(R.string.settings_read_page_scaling_fixed_scale),
                        ),
                        // 与 GalleryView.SCALE_* 同序；旧实现的 fixed scale 倍率恒为 1，等价于 actual size
                        selected = settings.scaleMode.coerceIn(0, 4),
                        onSelect = { onSettings(settings.copy(scaleMode = it)) },
                    )
                }
                item {
                    // 只有 actual size / fixed scale 才看得出效果：图比屏幕小时按这个角对齐
                    ChoiceRow(
                        label = stringResource(R.string.settings_read_start_position),
                        options = listOf(
                            stringResource(R.string.settings_read_start_position_top_left),
                            stringResource(R.string.settings_read_start_position_top_right),
                            stringResource(R.string.settings_read_start_position_bottom_left),
                            stringResource(R.string.settings_read_start_position_bottom_right),
                            stringResource(R.string.settings_read_start_position_center),
                        ),
                        selected = settings.startPosition.coerceIn(0, 4),
                        onSelect = { onSettings(settings.copy(startPosition = it)) },
                    )
                }
                item {
                    ChoiceRow(
                        label = stringResource(R.string.settings_read_theme),
                        options = listOf(
                            stringResource(R.string.settings_read_theme_follow_app),
                            stringResource(R.string.settings_read_theme_dark),
                            stringResource(R.string.settings_read_theme_light),
                        ),
                        selected = settings.readTheme.coerceIn(0, 2),
                        onSelect = { onSettings(settings.copy(readTheme = it)) },
                    )
                }
                item {
                    ChoiceRow(
                        label = stringResource(R.string.settings_read_screen_rotation),
                        options = listOf(
                            stringResource(R.string.settings_read_screen_rotation_default),
                            stringResource(R.string.settings_read_screen_rotation_portrait),
                            stringResource(R.string.settings_read_screen_rotation_landscape),
                            stringResource(R.string.settings_read_screen_rotation_sensor),
                        ),
                        selected = settings.screenRotation.coerceIn(0, 3),
                        onSelect = { onSettings(settings.copy(screenRotation = it)) },
                    )
                }
                item {
                    SwitchRow(
                        label = stringResource(R.string.settings_read_reading_fullscreen),
                        checked = settings.fullscreen,
                        onCheckedChange = { onSettings(settings.copy(fullscreen = it)) },
                    )
                }
                item {
                    SwitchRow(
                        label = stringResource(R.string.settings_read_keep_screen_on),
                        checked = settings.keepScreenOn,
                        onCheckedChange = { onSettings(settings.copy(keepScreenOn = it)) },
                    )
                }
                item {
                    SwitchRow(
                        label = stringResource(R.string.settings_read_show_page_interval),
                        checked = settings.showPageInterval,
                        onCheckedChange = { onSettings(settings.copy(showPageInterval = it)) },
                    )
                }
                item {
                    // 自动翻页：开关与间隔共用一个字段，0 = 关（与旧实现一样，开关本身不持久化）
                    IntervalRow(
                        label = stringResource(R.string.settings_read_turn_page_interval),
                        value = settings.autoPageInterval,
                        range = 0f..30f,
                        onValue = { onSettings(settings.copy(autoPageInterval = it)) },
                    )
                }
                item {
                    SwitchRow(
                        label = stringResource(R.string.settings_read_volume_page),
                        checked = settings.volumePage,
                        onCheckedChange = { onSettings(settings.copy(volumePage = it)) },
                    )
                }
                item {
                    // 上限与 `read_settings.xml` 的 max=14 对齐，否则在那边设了 14 会被这边写回 10
                    IntervalRow(
                        label = stringResource(R.string.settings_read_volume_page_interval),
                        value = settings.volumePageInterval,
                        range = 1f..14f,
                        onValue = { onSettings(settings.copy(volumePageInterval = it.coerceAtLeast(1))) },
                    )
                }
                item {
                    SwitchRow(
                        label = stringResource(R.string.settings_read_reverse_volume),
                        checked = settings.reverseVolumePage,
                        onCheckedChange = { onSettings(settings.copy(reverseVolumePage = it)) },
                    )
                }
                item {
                    SwitchRow(
                        label = stringResource(R.string.settings_read_custom_screen_lightness),
                        checked = settings.customScreenLightness,
                        onCheckedChange = { onSettings(settings.copy(customScreenLightness = it)) },
                    )
                }
                item {
                    IntervalRow(
                        label = stringResource(R.string.settings_read_screen_lightness),
                        value = settings.screenLightness,
                        range = 0f..200f,
                        onValue = { onSettings(settings.copy(screenLightness = it)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

/** 跳页：输入 1 基页码，越界钳到边界 */
@Composable
fun PagePickerDialog(
    count: Int,
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(current) { mutableStateOf((current + 1).toString()) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(7) },
                singleLine = true,
                suffix = { Text("/ $count") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = text.toIntOrNull()?.minus(1) ?: return@TextButton
                    onConfirm(target.coerceIn(0, (count - 1).coerceAtLeast(0)))
                    onDismiss()
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, option ->
                FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(option) })
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntervalRow(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拖动过程中先记在本地，松手才提交，免得每动一像素都改一次设置
    var dragging by remember { mutableFloatStateOf(-1f) }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$label: ${dragging.takeIf { it >= 0f }?.roundToInt() ?: value}")
        Slider(
            value = dragging.takeIf { it >= 0f } ?: value.toFloat(),
            valueRange = range,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging.takeIf { it >= 0f }?.let { onValue(it.roundToInt()) }
                dragging = -1f
            },
        )
    }
}
