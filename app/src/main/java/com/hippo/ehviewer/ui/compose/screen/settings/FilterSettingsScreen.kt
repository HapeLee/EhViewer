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

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhFilter

/** 过滤器列表的一行：分组标题，或一条过滤器 */
sealed interface FilterEntry {
    val key: String

    data class Header(val mode: Int, @param:StringRes val titleRes: Int) : FilterEntry {
        override val key: String get() = "header:$mode"
    }

    data class Item(
        val id: Long,
        /** 库里的原文，删除确认里显示它（与老实现一致） */
        val text: String,
        /** 列表里显示的文本，开了标签翻译时是「原文 (翻译)」 */
        val label: String,
        val enabled: Boolean,
    ) : FilterEntry {
        override val key: String get() = "item:$id"
    }
}

/**
 * 过滤器页（原 `FilterFragment` + `activity_filter.xml` + `item_filter(_header).xml`）。
 *
 * 交互口径没变：
 * - 顶部信息按钮弹「过滤器怎么用」；
 * - 空列表时居中显示 `no_filter` 与 `big_filter` 大图；
 * - 点条目切启停（未启用时文字带删除线，对得上 `MaterialCheckBox` 的老样子），
 *   行尾的垃圾桶删除，删除要先确认；
 * - 右下角 FAB 添加，类型就是 `array/filter_entries` 的六项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSettingsScreen(
    entries: List<FilterEntry>,
    onBack: () -> Unit,
    onAdd: (mode: Int, text: String, onResult: (Boolean) -> Unit) -> Unit,
    onToggle: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var showTipDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FilterEntry.Item?>(null) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.filter)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showTipDialog = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.tip),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_filter),
                )
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyFilterTip(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                // 给 FAB 留出位置，最后一条不会被压住
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is FilterEntry.Header -> FilterGroupHeader(entry.titleRes)
                        is FilterEntry.Item -> FilterItemRow(
                            item = entry,
                            onToggle = { onToggle(entry.id) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showTipDialog) {
        AlertDialog(
            onDismissRequest = { showTipDialog = false },
            title = { Text(text = stringResource(R.string.filter)) },
            text = { Text(text = stringResource(R.string.filter_tip)) },
            confirmButton = {
                TextButton(onClick = { showTipDialog = false }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showAddDialog) {
        AddFilterDialog(
            onDismiss = { showAddDialog = false },
            onAdd = onAdd,
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(text = stringResource(R.string.delete_filter, item.text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(item.id)
                    },
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyFilterTip(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.big_filter),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.no_filter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun FilterGroupHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterItemRow(
    item: FilterEntry.Item,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.enabled, onCheckedChange = null)
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            // 老 `MaterialCheckBox` 在未勾选时给文字加删除线，这里手动对齐
            textDecoration = if (item.enabled) null else TextDecoration.LineThrough,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
            )
        }
    }
}

/**
 * 添加对话框（原 `dialog_add_filter.xml` + `AddFilterDialogHelper`）。
 *
 * 校验口径没变：空文本提示 `text_is_empty`，与已有条目重复提示 `label_text_exist`
 * 并且**不关对话框**——所以 [onAdd] 的结果要回调回来。
 */
@Composable
private fun AddFilterDialog(
    onDismiss: () -> Unit,
    onAdd: (mode: Int, text: String, onResult: (Boolean) -> Unit) -> Unit,
) {
    val typeLabels = stringArrayResource(R.array.filter_entries).toList()
    var mode by rememberSaveable { mutableIntStateOf(EhFilter.MODE_TITLE) }
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    val emptyError = stringResource(R.string.text_is_empty)
    val existError = stringResource(R.string.label_text_exist)
    val keyboard = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.add_filter)) },
        text = {
            Column {
                Box {
                    OutlinedButton(
                        onClick = { typeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = typeLabels.getOrElse(mode) { "" })
                    }
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        typeLabels.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(text = label) },
                                onClick = {
                                    mode = index
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    label = { Text(text = stringResource(R.string.filter_text)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(text = message) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        error = emptyError
                        return@TextButton
                    }
                    error = null
                    adding = true
                    onAdd(mode, trimmed) { added ->
                        adding = false
                        if (added) {
                            onDismiss()
                        } else {
                            error = existError
                        }
                    }
                },
            ) {
                Text(text = stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
