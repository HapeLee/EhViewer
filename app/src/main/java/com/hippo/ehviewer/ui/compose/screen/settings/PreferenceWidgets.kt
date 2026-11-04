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

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.compose.component.DialogListItemSpacing
import com.hippo.ehviewer.ui.compose.component.DialogRadioItem
import kotlin.math.roundToInt

/**
 * 设置分类页的壳：顶栏（标题 + 返回）+ 可滚动内容列。
 *
 * 分类页没有抽屉入口（`EhRoute.SettingsCategory` 的 `hasLeftDrawer` 为 false），
 * 所以返回箭头是唯一的出口，系统返回键由 `NavDisplay` 直接弹栈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 顶部 8dp 让第一张卡不至于贴着顶栏，底部留出滚动到底的呼吸位
                .padding(top = 8.dp, bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** 分组外壳的四角半径：整组按这个圆角裁剪，组内各行再各自小圆角 */
private val SettingsGroupShape = RoundedCornerShape(28.dp)

/** 组内单行的圆角（MD3 spliced list 的 4dp 项目角） */
private val SettingsItemShape = RoundedCornerShape(4.dp)

/** 行与行之间的缝：外层没有底色，缝里透出的是页面底色，代替分割线 */
private val SettingsItemGap = 2.dp

/** 单行的最小高度：沿用迁到 Compose 之前的 48dp，别让整页比原来高一截 */
private val SettingsItemMinHeight = 40.dp

/**
 * 设置分组：外层大圆角裁剪 + 组内每行自带小圆角的卡片，行间留 2dp 缝。
 *
 * 外形与 legado 的 `SplicedColumnGroup` 一致：外层**不铺底色**，只做 `clip`——
 * 第一行的上沿、最后一行的下沿被裁成 28dp 大圆角，中间各行保留自己的 4dp 小角，
 * 2dp 的缝里透出页面底色，充当原先那根分割线。
 *
 * 这样组内不需要「第一条/最后一条」的判断，条件渲染（未登录少几项）也不会露出多余线条。
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(SettingsGroupShape),
        verticalArrangement = Arrangement.spacedBy(SettingsItemGap),
        content = content,
    )
}

/**
 * 组内一行的容器：小圆角卡片 + 整行涟漪。
 *
 * 行组件（[PreferenceRow] 等）都套在这一层，所以 `clip` 一定排在 `clickable` 之前，
 * 涟漪跟着 4dp 圆角走，不会溢到相邻行上。
 */
@Composable
fun SettingsItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SettingsItemShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/**
 * 设置页的横向复用点（§11.1 的 `PreferenceWidgets`）。
 *
 * 只做 M3 的行样式与交互，读写一律经 `Settings`（DataStore + 进程内快照），
 * 所以每一行自己就能保持响应式：改值之后同一 key 的所有订阅者（列表页、阅读器、
 * 其它设置页）都会跟着变，不需要「退出重进」。
 *
 * 行的一贯结构是「标题 / 摘要 + 尾部控件」，点击整行与点尾部等价——这与
 * 原来 `Preference` 的触区一致，也照顾了开关的小目标。
 *
 * 没有引第三方组件库：`me.zhanghai.compose.preference` 那套自带 `PreferenceStore`
 * 抽象，和 `Settings` 的「类型化 getter + 每 key 一条 Flow」对不上，桥接层反而比直接
 * 写这几个行多；而这里真正需要的只有开关、单选、滑杆三种。
 */
@Composable
fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    SettingsItem(modifier = modifier, enabled = enabled, onClick = onClick) {
        PreferenceRowContent(
            title = title,
            summary = summary,
            icon = icon,
            enabled = enabled,
            trailing = trailing,
        )
    }
}

/**
 * 一行的内容（标题 / 摘要 + 尾部控件），不含卡片壳。
 *
 * 拆出来是为了滑杆行：它要在一张卡里同时放标题行和滑杆，不能再套一层 [SettingsItem]。
 */
@Composable
private fun PreferenceRowContent(
    title: String,
    summary: String? = null,
    icon: Painter? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 分组自己已经留了 16dp 外边距，行内再用 16dp 就是 M3 列表的 32dp 视觉缩进
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = SettingsItemMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** 开关行：整行可点，开关本身不单独接手势（避免「点文字无效」） */
@Composable
fun SwitchPreferenceRow(
    key: String,
    default: Boolean,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int? = null,
    summaryOn: String? = null,
    summaryOff: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit = {},
) {
    val checked = Settings.valueFlow(key, default)
        .collectAsState(initial = Settings.getBoolean(key, default)).value
    PreferenceRow(
        title = stringResource(titleRes),
        // 老 XML 的 summaryOn / summaryOff：摘要跟着开关状态走
        summary = if (checked) summaryOn ?: summaryRes?.let { stringResource(it) } else summaryOff ?: summaryRes?.let { stringResource(it) },
        enabled = enabled,
        onClick = {
            val next = !checked
            Settings.putBoolean(key, next)
            onChange(next)
        },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * 单选行（原 `SimpleMenuPreference` / `ListPreference`）。
 *
 * 值按旧口径存成 String（`Settings.putIntToStr`），老 XML 页还在读同一批 key；
 * 摘要显示当前选中项的条目文本，点开是 M3 的单选对话框。
 */
@Composable
fun ListPreferenceRow(
    key: String,
    default: Int,
    @StringRes titleRes: Int,
    @ArrayRes entriesRes: Int,
    @ArrayRes valuesRes: Int,
    enabled: Boolean = true,
    summaryFormat: ((String) -> String)? = null,
    onChange: (Int) -> Unit = {},
) {
    val entries = stringArrayResource(entriesRes).toList()
    val values = stringArrayResource(valuesRes).map { it.toIntOrNull() ?: 0 }
    val current = Settings.valueFlowIntFromStr(key, default)
        .collectAsState(initial = Settings.getIntFromStr(key, default)).value
    var showDialog by rememberSaveable(key) { mutableStateOf(false) }

    PreferenceRow(
        title = stringResource(titleRes),
        summary = entries.getOrNull(values.indexOf(current))?.let { summaryFormat?.invoke(it) ?: it },
        enabled = enabled,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        PreferenceListDialog(
            title = stringResource(titleRes),
            entries = entries,
            selectedIndex = values.indexOf(current),
            onDismiss = { showDialog = false },
            onSelect = { index ->
                showDialog = false
                val value = values.getOrNull(index) ?: return@PreferenceListDialog
                Settings.putIntToStr(key, value)
                onChange(value)
            },
        )
    }
}

/** 单选行的字符串版本（`app_language` 这类「值本身有语义」的 key） */
@Composable
fun StringListPreferenceRow(
    key: String,
    default: String,
    @StringRes titleRes: Int,
    @ArrayRes entriesRes: Int,
    @ArrayRes valuesRes: Int,
    enabled: Boolean = true,
    summaryFormat: ((String) -> String)? = null,
    onChange: (String) -> Unit = {},
) {
    val entries = stringArrayResource(entriesRes).toList()
    val values = stringArrayResource(valuesRes).toList()
    val current = Settings.valueFlow(key, default)
        .collectAsState(initial = Settings.getString(key, default) ?: default).value
    var showDialog by rememberSaveable(key) { mutableStateOf(false) }

    PreferenceRow(
        title = stringResource(titleRes),
        summary = entries.getOrNull(values.indexOf(current))?.let { summaryFormat?.invoke(it) ?: it },
        enabled = enabled,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        PreferenceListDialog(
            title = stringResource(titleRes),
            entries = entries,
            selectedIndex = values.indexOf(current),
            onDismiss = { showDialog = false },
            onSelect = { index ->
                showDialog = false
                val value = values.getOrNull(index) ?: return@PreferenceListDialog
                Settings.putString(key, value)
                onChange(value)
            },
        )
    }
}

/**
 * 滑杆行（原 `SeekBarPreference`）。
 *
 * 拖动期间只更新本地状态，松手才落盘：DataStore 的每次写入都是一串事务，
 * 拖动期间逐格写会把写入队列排满。
 */
@Composable
fun SliderPreferenceRow(
    key: String,
    default: Int,
    @StringRes titleRes: Int,
    range: IntRange,
    @StringRes summaryRes: Int? = null,
    enabled: Boolean = true,
    asString: Boolean = false,
) {
    val stored = if (asString) {
        Settings.valueFlowIntFromStr(key, default)
            .collectAsState(initial = Settings.getIntFromStr(key, default)).value
    } else {
        Settings.valueFlow(key, default)
            .collectAsState(initial = Settings.getInt(key, default)).value
    }
    var local by remember(key) { mutableFloatStateOf(stored.toFloat()) }
    // 外部改了这个 key（别的页面 / 恢复默认）时把滑杆拉回去
    LaunchedEffect(stored) { local = stored.toFloat() }

    val commit: (Float) -> Unit = { value ->
        val intValue = value.roundToInt().coerceIn(range.first, range.last)
        if (asString) Settings.putIntToStr(key, intValue) else Settings.putInt(key, intValue)
    }

    // 标题行与滑杆同属一条设置项，所以整块共用一张卡
    SettingsItem {
        PreferenceRowContent(
            title = stringResource(titleRes),
            summary = summaryRes?.let { stringResource(it) },
            enabled = enabled,
            trailing = { Text(text = local.roundToInt().toString(), style = MaterialTheme.typography.bodyMedium) },
        )
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { commit(local) },
            enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.count() - 2).coerceAtLeast(0),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * 单选行，但值和外部持有的 [MutableState] 绑定（阅读器那批 `Settings.xxx.asMutableState()`
 * 字段，没有独立的字符串 key）。外观与 [ListPreferenceRow] 一致，避免阅读页和别的
 * 分类页长得不一样。
 */
@Composable
fun ListFieldRow(
    title: String,
    entries: List<String>,
    values: List<Int>,
    field: MutableState<Int>,
    enabled: Boolean = true,
) {
    var value by field
    var showDialog by rememberSaveable { mutableStateOf(false) }

    PreferenceRow(
        title = title,
        summary = entries.getOrNull(values.indexOf(value)),
        enabled = enabled,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        PreferenceListDialog(
            title = title,
            entries = entries,
            selectedIndex = values.indexOf(value),
            onDismiss = { showDialog = false },
            onSelect = { index ->
                showDialog = false
                values.getOrNull(index)?.let { value = it }
            },
        )
    }
}

/** 开关行，值与 [MutableState] 绑定；见 [ListFieldRow] */
@Composable
fun SwitchFieldRow(
    title: String,
    field: MutableState<Boolean>,
    enabled: Boolean = true,
) {
    var value by field
    PreferenceRow(
        title = title,
        enabled = enabled,
        onClick = { value = !value },
        trailing = { Switch(checked = value, onCheckedChange = null, enabled = enabled) },
    )
}

/** 单选对话框：竖向可滚，条目多（如主题 4 项、站点 2 项）时也不会撑出屏幕 */
@Composable
fun PreferenceListDialog(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
            ) {
                items(entries.size) { index ->
                    DialogRadioItem(
                        text = entries[index],
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
