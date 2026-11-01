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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.ui.compose.component.GalleryItem
import com.hippo.ehviewer.ui.compose.component.GalleryItemLayout
import com.hippo.ehviewer.ui.compose.component.GridCellMinWidth
import com.hippo.ehviewer.ui.compose.component.SearchPanel
import com.hippo.ehviewer.ui.compose.component.SearchPanelState
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 顶栏三种形态，对应旧的 `STATE_NORMAL / STATE_SIMPLE_SEARCH / STATE_SEARCH` */
object GalleryListUiMode {
    const val NORMAL = 0
    const val SIMPLE_SEARCH = 1
    const val SEARCH = 2
}

/** 榜单页右侧抽屉的一行 */
data class TopListRange(
    val id: String,
    val label: String,
)

/**
 * 画廊列表页的完整 UI 状态。
 *
 * 由一个不可变快照描述，Scene 每次状态变化都重新构造：这样组合层只依赖快照，
 * 不需要在 Composable 里持有业务状态（旧实现是 Scene 里一堆 View 字段）。
 */
data class GalleryListUiState(
    val title: String = "",
    val searchHint: String = "",
    val items: List<GalleryInfo> = emptyList(),
    val layoutMode: Int = GalleryItemLayout.LIST,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val emptyText: String? = null,
    val mode: Int = GalleryListUiMode.NORMAL,
    val isTopList: Boolean = false,
    val isPopular: Boolean = false,
    val topListRanges: List<TopListRange> = emptyList(),
    val selectedTopListRange: String? = null,
    val topListPages: Int = 0,
    val topListPage: Int = 0,
    val quickSearches: List<QuickSearch> = emptyList(),
    val keyword: String = "",
    /** 搜索框里粘贴画廊 / 分页链接时的跳转建议 */
    val suggestion: UrlSuggestionUi? = null,
    /** 就地修改 `GalleryInfo`（收藏槽位 / 下载角标）后自增，用来触发重组 */
    val version: Int = 0,
)

/**
 * 画廊列表页（原 `scene_gallery_list.xml` + `GalleryListScene` 的界面部分）。
 *
 * 结构调整：
 * - 左抽屉仍是宿主的导航抽屉（顶栏菜单图标打开）；右侧「快速搜索 / 榜单时间范围」抽屉
 *   搬进场景内的 `ModalNavigationDrawer`，靠 `LayoutDirection` 镜像成右侧，
 *   并设 `gesturesEnabled = false`，避免和宿主的左侧抽屉抢手势
 * - `SearchBar` 的三态换成顶栏三态：标题态（点标题进简单搜索）、简单搜索态、展开搜索态
 *   （展开搜索态下正文换成 [SearchPanel]）
 * - 4 个 mini FAB + primary FAB 折叠动效改成「刷新 FAB + 溢出菜单」
 * - 列表/网格共用 [GalleryItem]，跟随 `Settings.listMode`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryListScreen(
    state: GalleryListUiState,
    searchPanel: SearchPanelState,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenNavDrawer: () -> Unit,
    onEnterSimpleSearch: () -> Unit,
    onEnterSearch: () -> Unit,
    onExitSearch: () -> Unit,
    onApplySearch: (String) -> Unit,
    onSuggestionClick: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (GalleryInfo) -> Unit,
    onRead: (GalleryInfo) -> Unit,
    onDownload: (GalleryInfo) -> Unit,
    onDeleteDownload: (GalleryInfo) -> Unit,
    onToggleFavorite: (GalleryInfo) -> Unit,
    onMoveDownloadLabel: (GalleryInfo, Int) -> Unit,
    onToggleLayoutMode: () -> Unit,
    onGoToDate: () -> Unit,
    onGoToLastPage: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onGoToGid: (Long) -> Unit,
    onSaveQuickSearch: (String, Boolean, Long?) -> Unit,
    onSelectQuickSearch: (QuickSearch) -> Unit,
    onDeleteQuickSearch: (QuickSearch) -> Unit,
    onMoveQuickSearch: (Int, Int) -> Unit,
    onSelectTopListRange: (String) -> Unit,
    downloadLabelOptions: List<String>,
    pageCount: Int,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current

    val searchMode = state.mode == GalleryListUiMode.SEARCH
    val simpleSearch = state.mode == GalleryListUiMode.SIMPLE_SEARCH

    // 抽屉打开时优先关抽屉，其余返回键仍交给 Scene 处理（搜索态退出 → 双击退出）
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    var overflowVisible by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var deleteDownloadTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var moveLabelTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var goToPageDialog by remember { mutableStateOf(false) }
    var goToGidDialog by remember { mutableStateOf(false) }
    var saveQuickSearchDialog by remember { mutableStateOf(false) }
    var deleteQuickSearchTarget by remember { mutableStateOf<QuickSearch?>(null) }

    // 外层临时切成 RTL，让 ModalNavigationDrawer 从右侧滑出（镜像），
    // 抽屉内容与正文再各自切回 LTR，避免文字/布局方向被带歪。
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ListDrawerContent(
                        state = state,
                        onSelectQuickSearch = {
                            scope.launch { drawerState.close() }
                            onSelectQuickSearch(it)
                        },
                        onDeleteQuickSearch = { deleteQuickSearchTarget = it },
                        onMoveQuickSearch = onMoveQuickSearch,
                        onSelectTopListRange = {
                            scope.launch { drawerState.close() }
                            onSelectTopListRange(it)
                        },
                        onAddQuickSearch = { saveQuickSearchDialog = true },
                        rowHeightPx = with(density) { DRAWER_ROW_HEIGHT.toPx() },
                    )
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    topBar = {
                        when {
                            searchMode -> SearchTopBar(
                                hint = state.searchHint,
                                query = query,
                                onQueryChange = onQueryChange,
                                onBack = onExitSearch,
                                onSubmit = { onApplySearch(query) },
                            )
                            simpleSearch -> SearchTopBar(
                                hint = state.searchHint,
                                query = query,
                                onQueryChange = onQueryChange,
                                onBack = onExitSearch,
                                onSubmit = { onApplySearch(query) },
                            )
                            else -> GalleryListTopBar(
                                title = state.title,
                                onClickTitle = onEnterSimpleSearch,
                                onOpenNavDrawer = onOpenNavDrawer,
                                onEnterSearch = onEnterSearch,
                                onOpenListDrawer = { scope.launch { drawerState.open() } },
                                overflowVisible = overflowVisible,
                                onOverflowVisibleChange = { overflowVisible = it },
                                isTopList = state.isTopList,
                                isPopular = state.isPopular,
                                layoutMode = state.layoutMode,
                                onRefresh = onRefresh,
                                onGoToDate = onGoToDate,
                                onGoToLastPage = onGoToLastPage,
                                onGoToPage = { goToPageDialog = true },
                                onGoToGid = { goToGidDialog = true },
                                onToggleLayoutMode = onToggleLayoutMode,
                                onSaveQuickSearch = { saveQuickSearchDialog = true },
                            )
                        }
                    },
                    floatingActionButton = {
                        if (!searchMode) {
                            FloatingActionButton(onClick = onRefresh) {
                                Icon(
                                    painter = painterResource(R.drawable.v_refresh_dark_x24),
                                    contentDescription = stringResource(R.string.refresh),
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        // 搜索态下粘贴画廊/分页链接时给出的跳转建议（旧 `SearchBar.SuggestionProvider`）
                        if ((searchMode || simpleSearch) && state.suggestion != null) {
                            UrlSuggestionRow(
                                text = stringResource(R.string.gallery_list_search_bar_open_gallery),
                                onClick = onSuggestionClick,
                            )
                            HorizontalDivider()
                        }
                        Box(Modifier.fillMaxSize()) {
                            when {
                                searchMode -> SearchPanel(
                                    state = searchPanel,
                                    onPickImage = onPickImage,
                                    onClearImage = onClearImage,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                state.items.isEmpty() && state.error != null -> MessageState(
                                    text = state.error,
                                    actionText = stringResource(R.string.refresh),
                                    onAction = onRefresh,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                state.items.isEmpty() && state.loading -> CircularProgressIndicator(
                                    Modifier.align(Alignment.Center),
                                )
                                state.items.isEmpty() -> MessageState(
                                    text = state.emptyText ?: stringResource(R.string.gallery_list_empty_hit),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                state.layoutMode == GalleryItemLayout.GRID -> LazyVerticalGrid(
                                    columns = GridCells.Adaptive(GridCellMinWidth),
                                    state = gridState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    gridItems(items = state.items, key = { it.gid }) { info ->
                                        GalleryItem(
                                            info = info,
                                            layout = GalleryItemLayout.GRID,
                                            selected = false,
                                            selectionMode = false,
                                            onClick = { onOpenDetail(info) },
                                            onLongClick = { actionTarget = info },
                                        )
                                    }
                                    if (state.loadingMore) {
                                        item { LoadingMoreRow() }
                                    }
                                }
                                else -> LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                ) {
                                    items(items = state.items, key = { it.gid }) { info ->
                                        GalleryItem(
                                            info = info,
                                            layout = GalleryItemLayout.LIST,
                                            selected = false,
                                            selectionMode = false,
                                            onClick = { onOpenDetail(info) },
                                            onLongClick = { actionTarget = info },
                                        )
                                    }
                                    if (state.loadingMore) {
                                        item { LoadingMoreRow() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 触底加载
    LaunchedEffect(listState, gridState, state.layoutMode, state.items.size, state.hasMore, state.mode) {
        if (!state.hasMore || state.mode == GalleryListUiMode.SEARCH) return@LaunchedEffect
        val flow: Flow<Int> = if (state.layoutMode == GalleryItemLayout.GRID) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
        } else {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
        }
        flow.collect { last ->
            if (last >= 0 && last >= state.items.size - 5) onLoadMore()
        }
    }

    actionTarget?.let { info ->
        val downloaded = info.downloaded
        val favourited = info.favoriteSlot != -2
        val options = buildList {
            add(R.string.read to R.drawable.v_book_open_x24)
            add(
                (if (downloaded) R.string.delete_downloads else R.string.download) to
                    (if (downloaded) R.drawable.v_delete_x24 else R.drawable.v_download_x24),
            )
            add(
                (if (favourited) R.string.remove_from_favourites else R.string.add_to_favourites) to
                    (if (favourited) R.drawable.v_heart_broken_x24 else R.drawable.v_heart_x24),
            )
            if (downloaded) {
                add(R.string.download_move_dialog_title to R.drawable.v_folder_move_x24)
            }
        }
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                Text(
                    text = info.title.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column {
                    options.forEachIndexed { index, (labelRes, iconRes) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        actionTarget = null
                                        when (index) {
                                            0 -> onRead(info)
                                            1 -> if (downloaded) {
                                                deleteDownloadTarget = info
                                            } else {
                                                onDownload(info)
                                            }
                                            2 -> onToggleFavorite(info)
                                            else -> moveLabelTarget = info
                                        }
                                    },
                                    onLongClick = {},
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    deleteDownloadTarget?.let { info ->
        AlertDialog(
            onDismissRequest = { deleteDownloadTarget = null },
            title = { Text(stringResource(R.string.download_remove_dialog_title)) },
            text = {
                Text(stringResource(R.string.download_remove_dialog_message, info.title.orEmpty()))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDownloadTarget = null
                        onDeleteDownload(info)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDownloadTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    moveLabelTarget?.let { info ->
        AlertDialog(
            onDismissRequest = { moveLabelTarget = null },
            title = { Text(stringResource(R.string.download_move_dialog_title)) },
            text = {
                Column {
                    downloadLabelOptions.forEachIndexed { index, label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        moveLabelTarget = null
                                        onMoveDownloadLabel(info, index)
                                    },
                                    onLongClick = {},
                                )
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveLabelTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (goToPageDialog) {
        var input by remember { mutableStateOf((state.topListPage + 1).toString()) }
        NumberInputDialog(
            titleRes = R.string.go_to,
            initial = input,
            onDismiss = { goToPageDialog = false },
            onConfirm = { value ->
                goToPageDialog = false
                onGoToPage(value.toIntOrNull()?.coerceIn(1, pageCount) ?: 1)
            },
        )
    }

    if (goToGidDialog) {
        NumberInputDialog(
            titleRes = R.string.go_to,
            initial = "",
            onDismiss = { goToGidDialog = false },
            onConfirm = { value ->
                goToGidDialog = false
                onGoToGid(value.toLongOrNull() ?: 0L)
            },
        )
    }

    if (saveQuickSearchDialog) {
        val anchorGid = state.items.getOrNull(
            if (state.layoutMode == GalleryItemLayout.GRID) {
                gridState.firstVisibleItemIndex
            } else {
                listState.firstVisibleItemIndex
            },
        )?.gid
        QuickSearchDialog(
            defaultName = state.keyword.ifEmpty { state.title },
            onDismiss = { saveQuickSearchDialog = false },
            onConfirm = { name, saveProgress ->
                saveQuickSearchDialog = false
                onSaveQuickSearch(name, saveProgress, anchorGid)
            },
        )
    }

    deleteQuickSearchTarget?.let { quickSearch ->
        AlertDialog(
            onDismissRequest = { deleteQuickSearchTarget = null },
            title = { Text(stringResource(R.string.delete_quick_search_title)) },
            text = { Text(quickSearch.name.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteQuickSearchTarget = null
                        onDeleteQuickSearch(quickSearch)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteQuickSearchTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryListTopBar(
    title: String,
    onClickTitle: () -> Unit,
    onOpenNavDrawer: () -> Unit,
    onEnterSearch: () -> Unit,
    onOpenListDrawer: () -> Unit,
    overflowVisible: Boolean,
    onOverflowVisibleChange: (Boolean) -> Unit,
    isTopList: Boolean,
    isPopular: Boolean,
    layoutMode: Int,
    onRefresh: () -> Unit,
    onGoToDate: () -> Unit,
    onGoToLastPage: () -> Unit,
    onGoToPage: () -> Unit,
    onGoToGid: () -> Unit,
    onToggleLayoutMode: () -> Unit,
    onSaveQuickSearch: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(onClick = onClickTitle, onLongClick = onClickTitle),
            )
        },
        navigationIcon = {
            IconButton(onClick = onOpenNavDrawer) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_menu_24),
                    contentDescription = null,
                )
            }
        },
        actions = {
            IconButton(onClick = onEnterSearch) {
                Icon(
                    painter = painterResource(R.drawable.v_magnify_x24),
                    contentDescription = stringResource(R.string.search),
                )
            }
            IconButton(onClick = onOpenListDrawer) {
                Icon(
                    painter = if (isTopList) {
                        painterResource(R.drawable.ic_baseline_format_list_numbered_24)
                    } else {
                        painterResource(R.drawable.v_book_open_x24)
                    },
                    contentDescription = null,
                )
            }
            Box {
                IconButton(onClick = { onOverflowVisibleChange(true) }) {
                    Icon(
                        painter = painterResource(R.drawable.v_more_vert_dark_x24),
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = overflowVisible,
                    onDismissRequest = { onOverflowVisibleChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.refresh)) },
                        onClick = {
                            onOverflowVisibleChange(false)
                            onRefresh()
                        },
                    )
                    if (!isTopList && !isPopular) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.go_to)) },
                            onClick = {
                                onOverflowVisibleChange(false)
                                onGoToDate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.go_to) + " (gid)") },
                            onClick = {
                                onOverflowVisibleChange(false)
                                onGoToGid()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.go_to_last_page)) },
                            onClick = {
                                onOverflowVisibleChange(false)
                                onGoToLastPage()
                            },
                        )
                    }
                    if (isTopList) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.go_to)) },
                            onClick = {
                                onOverflowVisibleChange(false)
                                onGoToPage()
                            },
                        )
                    }
                    if (!isTopList) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (layoutMode == GalleryItemLayout.GRID) {
                                            R.string.list_mode
                                        } else {
                                            R.string.grid_mode
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                onOverflowVisibleChange(false)
                                onToggleLayoutMode()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_quick_search)) },
                            onClick = {
                                onOverflowVisibleChange(false)
                                onSaveQuickSearch()
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    hint: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.v_arrow_left_dark_x24),
                    contentDescription = null,
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(hint) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            IconButton(onClick = onSubmit) {
                Icon(
                    painter = painterResource(R.drawable.v_magnify_x24),
                    contentDescription = stringResource(R.string.search),
                )
            }
        },
    )
}

@Composable
private fun ListDrawerContent(
    state: GalleryListUiState,
    onSelectQuickSearch: (QuickSearch) -> Unit,
    onDeleteQuickSearch: (QuickSearch) -> Unit,
    onMoveQuickSearch: (Int, Int) -> Unit,
    onSelectTopListRange: (String) -> Unit,
    onAddQuickSearch: () -> Unit,
    rowHeightPx: Float,
) {
    ModalDrawerSheet {
        if (state.isTopList) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.toplist),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            state.topListRanges.forEach { range ->
                DrawerRow(
                    label = range.label,
                    selected = range.id == state.selectedTopListRange,
                    onClick = { onSelectTopListRange(range.id) },
                    trailing = null,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.quick_search),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAddQuickSearch) {
                    Icon(
                        painter = painterResource(R.drawable.v_plus_dark_x24),
                        contentDescription = stringResource(R.string.add_quick_search),
                    )
                }
            }
            HorizontalDivider()
            if (state.quickSearches.isEmpty()) {
                Text(
                    text = stringResource(R.string.add_quick_search_tip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn {
                    items(state.quickSearches.size, key = { state.quickSearches[it].id ?: it.toLong() }) { index ->
                        val quickSearch = state.quickSearches[index]
                        QuickSearchRow(
                            quickSearch = quickSearch,
                            index = index,
                            count = state.quickSearches.size,
                            rowHeightPx = rowHeightPx,
                            onClick = { onSelectQuickSearch(quickSearch) },
                            onLongClick = { onDeleteQuickSearch(quickSearch) },
                            onMove = onMoveQuickSearch,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSearchRow(
    quickSearch: QuickSearch,
    index: Int,
    count: Int,
    rowHeightPx: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DRAWER_ROW_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = quickSearch.name.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .pointerInput(index, count, rowHeightPx) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount.y
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
                painter = painterResource(R.drawable.v_more_vert_dark_x24),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UrlSuggestionRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.v_book_open_x24),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun LoadingMoreRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun MessageState(
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun NumberInputDialog(
    titleRes: Int,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun QuickSearchDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var saveProgress by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_quick_search)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.quick_search_name)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveProgress, onCheckedChange = { saveProgress = it })
                    Text(
                        text = stringResource(R.string.save_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), saveProgress) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private val DRAWER_ROW_HEIGHT = 56.dp

/** 旧实现用 `DownloadManager.getDownloadState(gid) != STATE_INVALID` 判定 */
private val GalleryInfo.downloaded: Boolean
    get() = com.hippo.ehviewer.download.DownloadManager.getDownloadState(gid) !=
        com.hippo.ehviewer.dao.DownloadInfo.STATE_INVALID
