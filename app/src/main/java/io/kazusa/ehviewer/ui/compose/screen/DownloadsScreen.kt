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
package io.kazusa.ehviewer.ui.compose.screen

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.getThumbKey
import io.kazusa.ehviewer.dao.DownloadInfo
import io.kazusa.ehviewer.support.yorozuya.FileUtils
import io.kazusa.ehviewer.ui.compose.component.CategoryBadge
import io.kazusa.ehviewer.ui.compose.component.DialogListItem
import io.kazusa.ehviewer.ui.compose.component.DialogListItemSpacing
import io.kazusa.ehviewer.ui.compose.component.DialogRadioItem
import io.kazusa.ehviewer.ui.compose.component.EhFabAction
import io.kazusa.ehviewer.ui.compose.component.EhFabMenu
import io.kazusa.ehviewer.ui.compose.component.EhSearchBarPage
import io.kazusa.ehviewer.ui.compose.component.GalleryItemLayout
import io.kazusa.ehviewer.ui.compose.component.ListItemShape
import io.kazusa.ehviewer.ui.compose.component.RatingBar
import io.kazusa.ehviewer.ui.compose.component.SearchHistorySuggestionList
import io.kazusa.ehviewer.ui.compose.component.rememberSearchBarScrollState
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
    /** 列表 / 网格形态，取值见 [GalleryItemLayout] */
    val listMode: Int = GalleryItemLayout.GRID,
    val version: Int = 0,
)

/**
 * 下载页（原 `scene_download.xml` + `DownloadsScene` 的界面部分）。
 *
 * 结构调整：
 * - 左侧导航抽屉仍归宿主；「下载标签」从右侧抽屉改成 bottom sheet（见 [LabelSheet]）：
 *   抽屉形态想避开宿主左侧抽屉的手势就得关掉 `gesturesEnabled`，连关闭手势一起没了
 * - 顶栏换成列表页统一的那根 M3 搜索条（[EhSearchBarPage]）：收起时是标题 + 抽屉按钮
 *   + 列表/网格切换 + 溢出菜单，点开就地搜下载标题（与「过滤 → 标题」共用一个槽位）
 * - 7 个 FAB 的多选操作台改成「多选顶栏 + 溢出菜单」：关闭、已选数量、全选、置顶 / 开始 / 停止 / 移动 / 删除
 * - 普通态的动作收敛成「FAB（刷新 / 排序 / 筛选）+ 溢出（全部开始 / 全部停止 / 反向开始 / 重置进度 / 标签）」
 * - 列表既支持自适应列宽的网格（[DownloadCard]），也支持列表（[DownloadListItem]），
 *   形态由 [DownloadsUiState.listMode] 决定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onOpenNavDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onToggleListMode: () -> Unit,
    onSearch: (String) -> Unit,
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
    val listState = rememberLazyListState()
    // 1.5 里 rememberModalBottomSheetState 已废弃，新的这版必须显式传初始值 Hidden
    val sheetState = rememberBottomSheetState(SheetValue.Hidden)
    var sheetVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 搜索条收缩位移 + FAB 显隐：与画廊列表页共用同一套状态机
    val scroll = rememberSearchBarScrollState()

    var searchExpanded by remember { mutableStateOf(false) }
    var query by remember(state.keyword) { mutableStateOf(state.keyword.orEmpty()) }
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

    // 搜索条展开时返回键先收起它，而不是退出页面
    BackHandler(enabled = searchExpanded) { searchExpanded = false }

    LaunchedEffect(state.scrollToGid, state.items, state.listMode) {
        val gid = state.scrollToGid ?: return@LaunchedEffect
        val index = state.items.indexOfFirst { it.gid == gid }
        if (index >= 0) {
            if (state.listMode == GalleryItemLayout.GRID) {
                gridState.scrollToItem(index)
            } else {
                listState.scrollToItem(index)
            }
        }
        onScrollToGidDone()
    }

    val content: @Composable (PaddingValues) -> Unit = { padding ->
        val contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 8.dp,
        )
        when {
            state.items.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                EmptyTip()
            }
            state.listMode == GalleryItemLayout.GRID -> LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                state = gridState,
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scroll.connection),
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
            else -> LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scroll.connection),
            ) {
                items(items = state.items, key = { it.gid }) { item ->
                    DownloadListItem(
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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (state.selectionMode) {
            // 多选态仍用上下文顶栏：这时搜索条没有意义，先让位给批量操作
            val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
            Scaffold(
                modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
                topBar = {
                    SelectionTopBar(
                        state = state,
                        onClose = onClearSelection,
                        onSelectAll = onSelectAll,
                        onPinTop = onPinTopSelected,
                        onStart = onStartSelected,
                        onStop = onStopSelected,
                        onMove = { moveDialog = true },
                        onDelete = { deleteDialog = true },
                        scrollBehavior = behavior,
                    )
                },
            ) { padding -> content(padding) }
        } else {
            EhSearchBarPage(
                title = state.title,
                hint = stringResource(R.string.favorites_search_bar_hint, state.title),
                query = query,
                expanded = searchExpanded,
                onQueryChange = { query = it },
                onApplySearch = onSearch,
                onExpandedChange = {
                    searchExpanded = it
                    scroll.reset(hideFab = it)
                },
                onOpenDrawer = onOpenNavDrawer,
                searchBarOffsetY = { scroll.offsetY },
                trailingIcon = {
                    // 列表 / 网格形态切换（对应参考项目下载页顶栏的那颗图标）
                    IconButton(onClick = onToggleListMode) {
                        Icon(
                            imageVector = if (state.listMode == GalleryItemLayout.GRID) {
                                Icons.AutoMirrored.Filled.ViewList
                            } else {
                                Icons.Filled.GridView
                            },
                            contentDescription = stringResource(
                                if (state.listMode == GalleryItemLayout.GRID) {
                                    R.string.list_mode
                                } else {
                                    R.string.grid_mode
                                },
                            ),
                        )
                    }
                    Box {
                        IconButton(onClick = { overflowVisible = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = overflowVisible,
                            onDismissRequest = { overflowVisible = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_start_all)) },
                                onClick = {
                                    overflowVisible = false
                                    onStartAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_stop_all)) },
                                onClick = {
                                    overflowVisible = false
                                    onStopAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_start_all_reversed)) },
                                onClick = {
                                    overflowVisible = false
                                    onStartAllReversed()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_reset_reading_progress)) },
                                onClick = {
                                    overflowVisible = false
                                    onResetReadingProgress()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download_labels)) },
                                onClick = {
                                    overflowVisible = false
                                    sheetVisible = true
                                },
                            )
                        }
                    }
                },
                expandedContent = {
                    SearchHistorySuggestionList(
                        query = query,
                        onPick = { picked ->
                            query = picked
                            onSearch(picked)
                        },
                    )
                },
            ) { padding -> content(padding) }
        }

        if (!state.selectionMode) {
            Box(Modifier.navigationBarsPadding()) {
                EhFabMenu(
                    visible = !scroll.fabHidden,
                    actions = listOf(
                        EhFabAction(Icons.Filled.Refresh, stringResource(R.string.refresh), onRefresh),
                        EhFabAction(
                            Icons.AutoMirrored.Filled.Sort,
                            stringResource(R.string.download_sort_by),
                        ) { sortDialog = true },
                        EhFabAction(
                            Icons.Filled.FilterList,
                            stringResource(R.string.download_filter),
                        ) { filterTypeDialog = true },
                    ),
                )
            }
        }
    }

    if (sheetVisible) {
        // 收起动画跑完再销毁，直接置 false 会把 sheet 硬切掉
        fun hideSheet() {
            scope.launch { sheetState.hide() }.invokeOnCompletion { sheetVisible = false }
        }
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            sheetState = sheetState,
        ) {
            LabelSheet(
                state = state,
                onSelectLabel = { label ->
                    onSelectLabel(label)
                    hideSheet()
                },
                onLongClickLabel = { labelMenuTarget = it },
                onMoveLabel = onMoveLabel,
                onAddLabel = { addLabelDialog = true },
                onSetDefaultLabel = { defaultLabelDialog = true },
                rowHeightPx = with(density) { LABEL_ROW_HEIGHT.toPx() },
            )
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
        var removeFiles by remember { mutableStateOf(io.kazusa.ehviewer.Settings.removeImageFiles) }
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
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
                ) {
                    items(options.size) { index ->
                        DialogListItem(
                            text = options[index],
                            onClick = {
                                moveDialog = false
                                onMoveSelected(if (index == 0) null else options[index])
                            },
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
                Column(verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing)) {
                    DialogListItem(
                        text = stringResource(R.string.rename_label),
                        onClick = {
                            labelMenuTarget = null
                            renameLabelTarget = target
                        },
                    )
                    DialogListItem(
                        text = stringResource(R.string.delete),
                        onClick = {
                            labelMenuTarget = null
                            deleteLabelTarget = target
                        },
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

/**
 * 列表形态的下载条目：缩略图在左，标题 / 状态 / 进度在右，右下角是开始 / 停止。
 *
 * 网格卡片（[DownloadCard]）讲究封面，列表形态则优先「一屏能看多少条 + 进度条够不够宽」，
 * 两者共用同一份 [DownloadItemUi]，点击 / 长按 / 封面的语义完全一致。
 */
@Composable
private fun DownloadListItem(
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
        ) {
            Box {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(DOWNLOAD_LIST_THUMB_HEIGHT)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(DOWNLOAD_LIST_THUMB_HEIGHT)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (item.state == DownloadInfo.STATE_DOWNLOAD) {
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
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
            // 开始 / 停止：贴右边缘，和卡片同一行高度居中
            Box(
                modifier = Modifier
                    .height(DOWNLOAD_LIST_THUMB_HEIGHT)
                    .padding(start = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = if (item.state == DownloadInfo.STATE_WAIT ||
                        item.state == DownloadInfo.STATE_DOWNLOAD
                    ) {
                        onStop
                    } else {
                        onStart
                    },
                ) {
                    Icon(
                        imageVector = if (item.state == DownloadInfo.STATE_WAIT ||
                            item.state == DownloadInfo.STATE_DOWNLOAD
                        ) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (item.state == DownloadInfo.STATE_WAIT ||
                                item.state == DownloadInfo.STATE_DOWNLOAD
                            ) {
                                R.string.download_stop
                            } else {
                                R.string.download_start
                            },
                        ),
                    )
                }
            }
        }
    }
}

private val DOWNLOAD_LIST_THUMB_HEIGHT = 96.dp

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
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var moreVisible by remember { mutableStateOf(false) }
    LargeTopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        },
        title = {
            Text(text = state.selectedCount.toString())
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = stringResource(R.string.check_all),
                )
            }
            Box {
                IconButton(onClick = { moreVisible = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
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
        scrollBehavior = scrollBehavior,
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
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        // 占位不再用 big_download 矢量（会被 Crop 切掉一半、还得靠 XML 主题
                        // 属性上色），直接铺一块表面色；加载完成后图会盖住它
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
                        imageVector = Icons.Filled.Check,
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
                                Icons.Filled.Pause,
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
                                Icons.Filled.PlayArrow,
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

/**
 * 下载标签选择面板。
 *
 * 原来是页面内的 `ModalNavigationDrawer`（靠外层 `LayoutDirection.Rtl` 让它从右侧出来）：
 * `gesturesEnabled = false` 让它既不响应遮罩点击也不能滑动关闭，退出只能靠返回键或选中某个标签。
 * 换成 bottom sheet 后，下滑 / 点遮罩 / 返回键三条路都通，也不再需要那套 RTL 镜像。
 */
@Composable
private fun LabelSheet(
    state: DownloadsUiState,
    onSelectLabel: (String?) -> Unit,
    onLongClickLabel: (String) -> Unit,
    onMoveLabel: (Int, Int) -> Unit,
    onAddLabel: () -> Unit,
    onSetDefaultLabel: () -> Unit,
    rowHeightPx: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.download_labels),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSetDefaultLabel) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.default_download_label),
                )
            }
            IconButton(onClick = onAddLabel) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add),
                )
            }
        }
        HorizontalDivider()
        // 标签通常只有几个，用普通 Column 让 sheet 高度跟着内容走
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
        ) {
            state.labels.forEachIndexed { index, label ->
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
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 与对话框里的列表项同一套圆角：sheet 容器是 surfaceContainerLow，行取高一档
    Surface(
        shape = ListItemShape,
        color = if (label.selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(LABEL_ROW_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
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
                        imageVector = Icons.Filled.Reorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
            ) {
                items(entries.size) { index ->
                    DialogRadioItem(
                        text = entries[index],
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
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
