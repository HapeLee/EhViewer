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
package com.hippo.ehviewer.ui.compose.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.ui.compose.component.CategoryBadge
import com.hippo.ehviewer.ui.compose.component.RatingBar
import com.hippo.yorozuya.FileUtils
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 下载条目在界面上的不可变快照（进度字段按值拷贝，避免读到旧对象） */
data class DownloadItemUi(
    val gid: Long,
    val title: String,
    val uploader: String?,
    val rating: Float,
    val category: Int,
    val thumbUrl: String?,
    val state: Int,
    val legacy: Int,
    val finished: Int,
    val total: Int,
    val speed: Long,
    val label: String?,
    val selected: Boolean,
)

/** 右侧标签抽屉的一行 */
data class DownloadLabelUi(
    val name: String?,
    val display: String,
    val count: Int,
    val selected: Boolean,
    /** “全部”“默认”两项不可拖动 */
    val draggable: Boolean,
)

data class DownloadsUiState(
    val title: String,
    val items: List<DownloadItemUi> = emptyList(),
    val labels: List<DownloadLabelUi> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedCount: Int = 0,
    val sort: Int = 0,
    val typeFilter: Int = -1,
    val keyword: String? = null,
    val scrollToGid: Long? = null,
    val version: Int = 0,
)

/**
 * 下载页（原 `scene_download.xml` + `DownloadsScene` 的界面部分）。
 *
 * 结构调整：
 * - 左侧导航抽屉仍归宿主；右侧「下载标签」抽屉搬进场景内的 `ModalNavigationDrawer`
 *   （RTL 镜像到右侧、`gesturesEnabled = false`，只由顶栏图标打开）
 * - 7 个 FAB 的多选操作台改成「多选顶栏 + 溢出菜单」：关闭、已选数量、全选、置顶 / 开始 / 停止 / 移动 / 删除
 * - 顶部菜单收敛成「过滤 / 全部开始 / 全部停止 / 溢出（排序 / 重置阅读进度 / 反向开始 / 标签抽屉）」
 * - 列表从 `AutoStaggeredGridLayoutManager` 换成自适应列宽的 `LazyVerticalGrid`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onOpenNavDrawer: () -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRead: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onStartOne: (Long) -> Unit,
    onStopOne: (Long) -> Unit,
    onPinTopSelected: () -> Unit,
    onStartSelected: () -> Unit,
    onStopSelected: () -> Unit,
    onDeleteSelected: (Boolean) -> Unit,
    onMoveSelected: (String?) -> Unit,
    onFilterType: (Int) -> Unit,
    onFilterTitle: (String) -> Unit,
    onSort: (Int) -> Unit,
    onStartAll: () -> Unit,
    onStopAll: () -> Unit,
    onStartAllReversed: () -> Unit,
    onResetReadingProgress: () -> Unit,
    onSelectLabel: (String?) -> Unit,
    onAddLabel: (String) -> Unit,
    onRenameLabel: (String, String) -> Unit,
    onDeleteLabel: (String) -> Unit,
    onMoveLabel: (Int, Int) -> Unit,
    onSetDefaultLabel: (Boolean, String?) -> Unit,
    onScrollToGidDone: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var overflowVisible by remember { mutableStateOf(false) }
    var filterTypeDialog by remember { mutableStateOf(false) }
    var filterTitleDialog by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var addLabelDialog by remember { mutableStateOf(false) }
    var renameLabelTarget by remember { mutableStateOf<String?>(null) }
    var deleteLabelTarget by remember { mutableStateOf<String?>(null) }
    var defaultLabelDialog by remember { mutableStateOf(false) }
    var labelMenuTarget by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(state.scrollToGid, state.items) {
        val gid = state.scrollToGid ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.gid == gid }
        if (index >= 0) gridState.scrollToItem(index)
        onScrollToGidDone()
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet {
                        LabelDrawer(
                            state = state,
                            onSelectLabel = {
                                scope.launch { drawerState.close() }
                                onSelectLabel(it)
                            },
                            onLongClickLabel = { labelMenuTarget = it },
                            onMoveLabel = onMoveLabel,
                            onAddLabel = { addLabelDialog = true },
                            onSetDefaultLabel = { defaultLabelDialog = true },
                            rowHeightPx = with(density) { LABEL_ROW_HEIGHT.toPx() },
                        )
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.fillMaxSize()) {
                    if (state.selectionMode) {
                        SelectionTopBar(
                            state = state,
                            onClose = onClearSelection,
                            onSelectAll = onSelectAll,
                            onPinTop = onPinTopSelected,
                            onStart = onStartSelected,
                            onStop = onStopSelected,
                            onMove = { moveDialog = true },
                            onDelete = { deleteDialog = true },
                        )
                    } else {
                        DownloadsTopBar(
                            title = state.title,
                            onOpenNavDrawer = onOpenNavDrawer,
                            onFilter = { filterTypeDialog = true },
                            onStartAll = onStartAll,
                            onStopAll = onStopAll,
                            onOpenLabels = { scope.launch { drawerState.open() } },
                            overflowVisible = overflowVisible,
                            onOverflowVisibleChange = { overflowVisible = it },
                            onSort = { sortDialog = true },
                            onResetReadingProgress = onResetReadingProgress,
                            onStartAllReversed = onStartAllReversed,
                        )
                    }

                    if (state.items.isEmpty()) {
                        EmptyTip()
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(132.dp),
                            state = gridState,
                            contentPadding = PaddingValues(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            gridItems(items = state.items, key = { it.gid }) { item ->
                                DownloadCard(
                                    item = item,
                                    selectionMode = state.selectionMode,
                                    onClick = {
                                        if (state.selectionMode) onToggleSelect(item.gid) else onRead(item.gid)
                                    },
                                    onLongClick = { onToggleSelect(item.gid) },
                                    onThumbClick = { onOpenDetail(item.gid) },
                                    onStart = { onStartOne(item.gid) },
                                    onStop = { onStopOne(item.gid) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (filterTypeDialog) {
        val entries = stringArrayResource(R.array.download_state).toList()
        SingleChoiceDialog(
            title = stringResource(R.string.download_filter),
            entries = entries,
            selected = state.typeFilter + 1,
            onDismiss = { filterTypeDialog = false },
            onSelect = { index ->
                filterTypeDialog = false
                if (index == FILTER_TITLE_INDEX) {
                    filterTitleDialog = true
                } else {
                    onFilterType(index - 1)
                }
            },
        )
    }

    if (filterTitleDialog) {
        TextInputDialog(
            title = stringResource(R.string.download_filter_title),
            initial = state.keyword.orEmpty(),
            emptyErrorRes = R.string.text_is_empty,
            onDismiss = { filterTitleDialog = false },
            onConfirm = {
                filterTitleDialog = false
                onFilterTitle(it)
            },
        )
    }

    if (sortDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.download_sort_by),
            entries = stringArrayResource(R.array.download_sort).toList(),
            selected = state.sort,
            onDismiss = { sortDialog = false },
            onSelect = {
                sortDialog = false
                onSort(it)
            },
        )
    }

    if (deleteDialog) {
        var removeFiles by remember { mutableStateOf(com.hippo.ehviewer.Settings.removeImageFiles) }
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(stringResource(R.string.download_remove_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.download_remove_dialog_message_2,
                            state.selectedCount,
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = removeFiles, onCheckedChange = { removeFiles = it })
                        Text(stringResource(R.string.download_remove_dialog_check_text))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialog = false
                        onDeleteSelected(removeFiles)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (moveDialog) {
        val options = listOf(stringResource(R.string.default_download_label_name)) +
            state.labels.filter { it.draggable }.map { it.display }
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text(stringResource(R.string.download_move_dialog_title)) },
            text = {
                Column {
                    options.forEachIndexed { index, label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        moveDialog = false
                                        onMoveSelected(if (index == 0) null else label)
                                    },
                                    onLongClick = {},
                                )
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (addLabelDialog) {
        TextInputDialog(
            title = stringResource(R.string.new_label_title),
            initial = "",
            emptyErrorRes = R.string.label_text_is_empty,
            onDismiss = { addLabelDialog = false },
            onConfirm = {
                addLabelDialog = false
                onAddLabel(it)
            },
        )
    }

    renameLabelTarget?.let { target ->
        TextInputDialog(
            title = stringResource(R.string.rename_label_title),
            initial = target,
            emptyErrorRes = R.string.label_text_is_empty,
            onDismiss = { renameLabelTarget = null },
            onConfirm = { newName ->
                renameLabelTarget = null
                onRenameLabel(target, newName)
            },
        )
    }

    deleteLabelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteLabelTarget = null },
            title = { Text(stringResource(R.string.delete_label_title)) },
            text = { Text(stringResource(R.string.delete_label_message, target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteLabelTarget = null
                        onDeleteLabel(target)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteLabelTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (defaultLabelDialog) {
        val entries = listOf(stringResource(R.string.let_me_select)) +
            listOf(stringResource(R.string.default_download_label_name)) +
            state.labels.filter { it.draggable }.map { it.display }
        SingleChoiceDialog(
            title = stringResource(R.string.default_download_label),
            entries = entries,
            selected = -1,
            onDismiss = { defaultLabelDialog = false },
            onSelect = { index ->
                defaultLabelDialog = false
                when {
                    index == 0 -> onSetDefaultLabel(false, null)
                    index == 1 -> onSetDefaultLabel(true, null)
                    else -> onSetDefaultLabel(true, entries[index])
                }
            },
        )
    }

    // 标签长按菜单：重命名 / 删除
    labelMenuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { labelMenuTarget = null },
            title = { Text(target) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.rename_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    labelMenuTarget = null
                                    renameLabelTarget = target
                                },
                                onLongClick = {},
                            )
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    labelMenuTarget = null
                                    deleteLabelTarget = target
                                },
                                onLongClick = {},
                            )
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { labelMenuTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsTopBar(
    title: String,
    onOpenNavDrawer: () -> Unit,
    onFilter: () -> Unit,
    onStartAll: () -> Unit,
    onStopAll: () -> Unit,
    onOpenLabels: () -> Unit,
    overflowVisible: Boolean,
    onOverflowVisibleChange: (Boolean) -> Unit,
    onSort: () -> Unit,
    onResetReadingProgress: () -> Unit,
    onStartAllReversed: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = onOpenNavDrawer) {
                Icon(painterResource(R.drawable.ic_baseline_menu_24), contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onFilter) {
                Icon(
                    painterResource(R.drawable.v_filter_dark_x24),
                    contentDescription = stringResource(R.string.download_filter),
                )
            }
            IconButton(onClick = onStartAll) {
                Icon(
                    painterResource(R.drawable.v_play_x24),
                    contentDescription = stringResource(R.string.download_start_all),
                )
            }
            IconButton(onClick = onStopAll) {
                Icon(
                    painterResource(R.drawable.v_pause_x24),
                    contentDescription = stringResource(R.string.download_stop_all),
                )
            }
            Box {
                IconButton(onClick = { onOverflowVisibleChange(true) }) {
                    Icon(
                        painterResource(R.drawable.v_more_vert_dark_x24),
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = overflowVisible,
                    onDismissRequest = { onOverflowVisibleChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_sort_by)) },
                        onClick = {
                            onOverflowVisibleChange(false)
                            onSort()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_start_all_reversed)) },
                        onClick = {
                            onOverflowVisibleChange(false)
                            onStartAllReversed()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_reset_reading_progress)) },
                        onClick = {
                            onOverflowVisibleChange(false)
                            onResetReadingProgress()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_labels)) },
                        onClick = {
                            onOverflowVisibleChange(false)
                            onOpenLabels()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    state: DownloadsUiState,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPinTop: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var moreVisible by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.v_close_dark_x24), contentDescription = null)
            }
        },
        title = {
            Text(
                text = state.selectedCount.toString(),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    painterResource(R.drawable.v_check_all_dark_x24),
                    contentDescription = stringResource(R.string.check_all),
                )
            }
            Box {
                IconButton(onClick = { moreVisible = true }) {
                    Icon(painterResource(R.drawable.v_more_vert_dark_x24), contentDescription = null)
                }
                DropdownMenu(expanded = moreVisible, onDismissRequest = { moreVisible = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_start)) },
                        onClick = {
                            moreVisible = false
                            onStart()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_stop)) },
                        onClick = {
                            moreVisible = false
                            onStop()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_pin_top)) },
                        onClick = {
                            moreVisible = false
                            onPinTop()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_move_dialog_title)) },
                        onClick = {
                            moreVisible = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            moreVisible = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun DownloadCard(
    item: DownloadItemUi,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onThumbClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val thumbKey = remember(item.gid) { getThumbKey(item.gid) }
    val request = remember(thumbKey, item.thumbUrl) {
        ImageRequest.Builder(context)
            .data(item.thumbUrl)
            .apply {
                memoryCacheKey(thumbKey)
                diskCacheKey(thumbKey)
            }
            .crossfade(true)
            .build()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = if (item.selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (item.selected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Box {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    placeholder = painterResource(R.drawable.big_download),
                    error = painterResource(R.drawable.big_download),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .combinedClickable(onClick = onThumbClick, onLongClick = onLongClick),
                )
                CategoryBadge(
                    category = item.category,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                )
                if (selectionMode && item.selected) {
                    Icon(
                        painter = painterResource(R.drawable.v_check_all_dark_x24),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
                when (item.state) {
                    DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    ) {
                        IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painterResource(R.drawable.v_pause_x24),
                                contentDescription = stringResource(R.string.download_stop),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    else -> Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                    ) {
                        IconButton(onClick = onStart, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painterResource(R.drawable.v_play_x24),
                                contentDescription = stringResource(R.string.download_start),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (item.state == DownloadInfo.STATE_DOWNLOAD) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    if (item.total > 0 && item.finished >= 0) {
                        LinearProgressIndicator(
                            progress = { item.finished.toFloat() / item.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = if (item.total > 0 && item.finished >= 0) {
                                "${item.finished} / ${item.total}"
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = FileUtils.humanReadableByteCount(item.speed.coerceAtLeast(0), false) + "/S",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RatingBar(rating = item.rating)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stateText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun stateText(item: DownloadItemUi): String = when (item.state) {
    DownloadInfo.STATE_NONE -> stringResource(R.string.download_state_none)
    DownloadInfo.STATE_WAIT -> stringResource(R.string.download_state_wait)
    DownloadInfo.STATE_FINISH -> stringResource(R.string.download_state_finish)
    DownloadInfo.STATE_FAILED -> if (item.legacy <= 0) {
        stringResource(R.string.download_state_failed)
    } else {
        stringResource(R.string.download_state_failed_2, item.legacy)
    }
    DownloadInfo.STATE_DOWNLOAD -> stringResource(R.string.download_state_downloading)
    else -> ""
}

@Composable
private fun LabelDrawer(
    state: DownloadsUiState,
    onSelectLabel: (String?) -> Unit,
    onLongClickLabel: (String) -> Unit,
    onMoveLabel: (Int, Int) -> Unit,
    onAddLabel: () -> Unit,
    onSetDefaultLabel: () -> Unit,
    rowHeightPx: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.download_labels),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSetDefaultLabel) {
            Icon(
                painterResource(R.drawable.v_download_box_dark_x24),
                contentDescription = stringResource(R.string.default_download_label),
            )
        }
        IconButton(onClick = onAddLabel) {
            Icon(
                painterResource(R.drawable.v_plus_dark_x24),
                contentDescription = stringResource(R.string.add),
            )
        }
    }
    HorizontalDivider()
    LazyColumn {
        items(state.labels.size, key = { state.labels[it].display }) { index ->
            val label = state.labels[index]
            LabelRow(
                label = label,
                index = index,
                count = state.labels.size,
                rowHeightPx = rowHeightPx,
                onClick = { onSelectLabel(label.name) },
                onLongClick = { label.name?.let(onLongClickLabel) },
                onMove = onMoveLabel,
            )
        }
    }
}

@Composable
private fun LabelRow(
    label: DownloadLabelUi,
    index: Int,
    count: Int,
    rowHeightPx: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    var dragOffset by remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LABEL_ROW_HEIGHT)
            .background(
                if (label.selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${label.display} [${label.count}]",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (label.selected) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (label.draggable) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .pointerInput(index, count, rowHeightPx) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val steps = (dragOffset / rowHeightPx).roundToInt()
                                val target = (index + steps).coerceIn(0, count - 1)
                                dragOffset = 0f
                                if (target != index) onMove(index, target)
                            },
                            onDragCancel = { dragOffset = 0f },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_reorder_24),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyTip() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.big_download),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.no_download_info),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    entries: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(entries.size) { index ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onSelect(index) }, onLongClick = {}),
                    ) {
                        RadioButton(selected = index == selected, onClick = { onSelect(index) })
                        Text(text = entries[index], style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    emptyErrorRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    error = false
                },
                singleLine = true,
                isError = error,
                supportingText = if (error) {
                    { Text(stringResource(emptyErrorRes)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val text = value.trim()
                    if (text.isEmpty()) {
                        error = true
                    } else {
                        onConfirm(text)
                    }
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private const val FILTER_TITLE_INDEX = 6

private val LABEL_ROW_HEIGHT = 56.dp
