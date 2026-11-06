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

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SavedSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.data.GalleryInfo
import io.kazusa.ehviewer.dao.QuickSearch
import io.kazusa.ehviewer.ui.compose.component.DialogListItem
import io.kazusa.ehviewer.ui.compose.component.DialogListItemSpacing
import io.kazusa.ehviewer.ui.compose.component.EhFabAction
import io.kazusa.ehviewer.ui.compose.component.EhFabMenu
import io.kazusa.ehviewer.ui.compose.component.EhPullToRefreshBox
import io.kazusa.ehviewer.ui.compose.component.EhSearchBarPage
import io.kazusa.ehviewer.ui.compose.component.FastScrollLazyColumn
import io.kazusa.ehviewer.ui.compose.component.FastScrollLazyVerticalGrid
import io.kazusa.ehviewer.ui.compose.component.GalleryItem
import io.kazusa.ehviewer.ui.compose.component.GalleryItemLayout
import io.kazusa.ehviewer.ui.compose.component.GridCellMinWidth
import io.kazusa.ehviewer.ui.compose.component.ListItemShape
import io.kazusa.ehviewer.ui.compose.component.SearchPanel
import io.kazusa.ehviewer.ui.compose.component.SearchPanelState
import io.kazusa.ehviewer.ui.compose.component.SearchSuggestionList
import io.kazusa.ehviewer.ui.compose.component.SearchSuggestionType
import io.kazusa.ehviewer.ui.compose.component.SearchSuggestionUi
import io.kazusa.ehviewer.ui.compose.component.UrlSuggestionListItem
import io.kazusa.ehviewer.ui.compose.component.rememberSearchBarScrollState
import io.kazusa.ehviewer.ui.compose.nav.NavTransitions
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 搜索条的展开形态，对应旧的 `STATE_NORMAL / STATE_SIMPLE_SEARCH / STATE_SEARCH`。
 *
 * 现在没有「搜索页」了：三种形态共用同一个列表页，区别只是顶栏搜索条是收起、
 * 展开成「简单联想」还是展开成「搜索面板 + 联想」。
 */
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
 * - **顶栏就是一个 M3 搜索条**（[EhSearchBarPage]，与 EHz 的 `SearchBarScreen` 同构）：
 *   收起时显示页面标题 + 抽屉按钮 + 右侧图标，点开就地长成 docked 表，
 *   里面是搜索面板（[SearchPanel]）或「进高级搜索」入口 + 历史 + 标签联想；
 *   正文始终是这一份列表，不再有独立的搜索页
 * - 左抽屉仍是宿主的导航抽屉（搜索条左侧菜单图标打开）；「快速搜索」从右侧抽屉改成了
 *   `ModalBottomSheet`：不用再靠 `LayoutDirection` 镜像，也不会和宿主的左抽屉抢手势；
 *   榜单时间范围则挪到了右下角的 FAB 菜单
 * - 4 个 mini FAB + primary FAB 折叠动效改成「刷新 FAB + 溢出菜单」（溢出菜单挂在搜索条右侧）
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
    searchSuggestions: List<SearchSuggestionUi>,
    onSelectSuggestion: (SearchSuggestionUi) -> Unit,
    onDeleteSuggestion: (SearchSuggestionUi) -> Unit,
    onSuggestionClick: () -> Unit,
    onPickImage: () -> Unit,
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
    val scope = rememberCoroutineScope()
    var quickSearchSheetVisible by remember { mutableStateOf(false) }
    val quickSearchSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current

    val searchMode = state.mode == GalleryListUiMode.SEARCH

    var overflowVisible by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var deleteDownloadTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var moveLabelTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var goToPageDialog by remember { mutableStateOf(false) }
    var goToGidDialog by remember { mutableStateOf(false) }
    var saveQuickSearchDialog by remember { mutableStateOf(false) }
    var deleteQuickSearchTarget by remember { mutableStateOf<QuickSearch?>(null) }

    val searchExpanded = state.mode != GalleryListUiMode.NORMAL

    // 列表滚动联动（参考项目同款）：搜索条上推的位移、FAB 是否隐藏/展开
    val scroll = rememberSearchBarScrollState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        // 顶栏就是一个 M3 搜索条（照抄 EHz 的 `SearchBarScreen`）：收起时浮在列表上方，
        // 展开时同一根条长成 docked 表，里面是搜索面板 + 历史 + 标签联想；正文始终是列表。
        EhSearchBarPage(
            title = state.title,
            hint = state.searchHint,
            query = query,
            expanded = searchExpanded,
            onQueryChange = onQueryChange,
            onApplySearch = onApplySearch,
            onExpandedChange = { expanded ->
                if (expanded) onEnterSearch() else onExitSearch()
                scroll.reset(hideFab = expanded)
            },
            onOpenDrawer = onOpenNavDrawer,
            onOpenImageSearch = onPickImage,
            searchBarOffsetY = { scroll.offsetY },
            trailingIcon = {
                // 榜单页的「排行时间」在 FAB 里，顶栏不再留快速搜索入口
                if (!state.isTopList) {
                    IconButton(onClick = { quickSearchSheetVisible = true }) {
                        Icon(Icons.Filled.SavedSearch, contentDescription = null)
                    }
                }
                Box {
                    IconButton(onClick = { overflowVisible = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = overflowVisible,
                        onDismissRequest = { overflowVisible = false },
                    ) {
                        if (!state.isTopList && !state.isPopular) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.go_to) + " (gid)") },
                                onClick = {
                                    overflowVisible = false
                                    goToGidDialog = true
                                },
                            )
                        }
                        if (!state.isTopList) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.layoutMode == GalleryItemLayout.GRID) {
                                                R.string.list_mode
                                            } else {
                                                R.string.grid_mode
                                            },
                                        ),
                                    )
                                },
                                onClick = {
                                    overflowVisible = false
                                    onToggleLayoutMode()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_quick_search)) },
                                onClick = {
                                    overflowVisible = false
                                    saveQuickSearchDialog = true
                                },
                            )
                        }
                    }
                }
            },
            expandedContent = {
                SearchSuggestionList(
                    suggestions = searchSuggestions,
                    onClick = onSelectSuggestion,
                    onDelete = onDeleteSuggestion,
                    // 参考项目把筛选放在联想列表最前面，这里同款
                    leading = {
                        SearchPanel(state = searchPanel)
                        HorizontalDivider()
                    },
                    header = state.suggestion?.let {
                        {
                            UrlSuggestionListItem(
                                text = stringResource(R.string.gallery_list_search_bar_open_gallery),
                                onClick = onSuggestionClick,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            // 内容按 contentPadding 内缩、列表本身铺满窗口：滚动时条目从搜索条 / 导航栏底下穿过去
            val contentTop = padding.calculateTopPadding() + 8.dp
            val contentBottom = padding.calculateBottomPadding() + 8.dp
            // 下拉刷新取代了原来溢出菜单里的「刷新」项：isRefreshing 只认「已有内容时
            // 的重新拉取」，首次加载仍走下面的居中转圈，免得一进页面就冒指示器
            EhPullToRefreshBox(
                isRefreshing = state.loading && state.items.isNotEmpty(),
                onRefresh = onRefresh,
                // 搜索条展开时 docked 表盖在列表上，背后的列表不该被下拉手势打扰
                enabled = !searchExpanded,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.items.isEmpty() && state.error != null -> MessageState(
                        text = state.error,
                        actionText = stringResource(R.string.refresh),
                        onAction = onRefresh,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                    state.items.isEmpty() && state.loading -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                    )
                    state.items.isEmpty() -> MessageState(
                        text = state.emptyText ?: stringResource(R.string.gallery_list_empty_hit),
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                    // 列表/网格都挂上快速滑动条：上千条时靠惯性滑到中间太费劲
                    state.layoutMode == GalleryItemLayout.GRID -> FastScrollLazyVerticalGrid(
                        columns = GridCells.Adaptive(GridCellMinWidth),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().nestedScroll(scroll.connection),
                        contentPadding = PaddingValues(top = contentTop, bottom = contentBottom),
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
                    else -> FastScrollLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().nestedScroll(scroll.connection),
                        contentPadding = PaddingValues(top = contentTop, bottom = contentBottom),
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
        // FAB 叠在页面上（不放 Scaffold 的 FAB 槽），两处都用 M3E 的 FAB menu
        Box(Modifier.navigationBarsPadding()) {
            if (state.isTopList) {
                TopListRangeFabMenu(
                    ranges = state.topListRanges,
                    selectedId = state.selectedTopListRange,
                    onSelect = onSelectTopListRange,
                )
            } else {
                EhFabMenu(
                    visible = !scroll.fabHidden,
                    actions = listOf(
                        EhFabAction(Icons.Filled.Refresh, stringResource(R.string.refresh), onRefresh),
                        EhFabAction(Icons.Filled.DateRange, stringResource(R.string.go_to), onGoToDate),
                        EhFabAction(
                            Icons.AutoMirrored.Filled.LastPage,
                            stringResource(R.string.go_to_last_page),
                            onGoToLastPage,
                        ),
                    ),
                )
            }
        }
    }

    // 快速搜索原来是右侧抽屉（靠 RTL 镜像滑出），现在改成底部 sheet：
    // 入口和内容都没变，但竖向手势和「下滑关闭」都交给 ModalBottomSheet 自己管
    if (quickSearchSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { quickSearchSheetVisible = false },
            sheetState = quickSearchSheetState,
        ) {
            QuickSearchSheetContent(
                state = state,
                onSelectQuickSearch = {
                    // 先让 sheet 自己收起来再切页面，直接改 visible 会硬切、没有下滑动画
                    scope.launch { quickSearchSheetState.hide() }.invokeOnCompletion {
                        if (!quickSearchSheetState.isVisible) quickSearchSheetVisible = false
                    }
                    onSelectQuickSearch(it)
                },
                onDeleteQuickSearch = { deleteQuickSearchTarget = it },
                onMoveQuickSearch = onMoveQuickSearch,
                onAddQuickSearch = { saveQuickSearchDialog = true },
                rowHeightPx = with(density) { SHEET_ROW_HEIGHT.toPx() },
            )
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
            add(R.string.read to Icons.AutoMirrored.Filled.MenuBook)
            add(
                (if (downloaded) R.string.delete_downloads else R.string.download) to
                    (if (downloaded) Icons.Filled.Delete else Icons.Filled.FileDownload),
            )
            add(
                (if (favourited) R.string.remove_from_favourites else R.string.add_to_favourites) to
                    (if (favourited) Icons.Filled.HeartBroken else Icons.Filled.Favorite),
            )
            if (downloaded) {
                add(R.string.download_move_dialog_title to Icons.Filled.DriveFileMove)
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
                Column(verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing)) {
                    options.forEachIndexed { index, (labelRes, icon) ->
                        DialogListItem(
                            text = stringResource(labelRes),
                            leadingIcon = icon,
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
                        )
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
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
                ) {
                    items(downloadLabelOptions.size) { index ->
                        DialogListItem(
                            text = downloadLabelOptions[index],
                            onClick = {
                                moveLabelTarget = null
                                onMoveDownloadLabel(info, index)
                            },
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

@Composable
private fun QuickSearchSheetContent(
    state: GalleryListUiState,
    onSelectQuickSearch: (QuickSearch) -> Unit,
    onDeleteQuickSearch: (QuickSearch) -> Unit,
    onMoveQuickSearch: (Int, Int) -> Unit,
    onAddQuickSearch: () -> Unit,
    rowHeightPx: Float,
) {
    // sheet 里只留一个可滚动容器：表头、空态提示、行全部塞进同一个 LazyColumn，
    // 否则内层 LazyColumn 的滚动会和 ModalBottomSheet 自己的拖拽抢手势
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item {
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
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_quick_search),
                    )
                }
            }
            HorizontalDivider()
        }
        if (state.quickSearches.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.add_quick_search_tip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
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
    // 与对话框里的列表项同一套圆角：sheet 容器是 surfaceContainerLow，行取高一档
    Surface(
        shape = ListItemShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(SHEET_ROW_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = quickSearch.name.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
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
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 榜单页的「排行时间」入口：M3E 的 FAB menu（`FloatingActionButtonMenu` + `ToggleFloatingActionButton`）。
 *
 * 原来这个入口在顶栏（列表图标 → 右侧抽屉），搬过来之后列表页的抽屉只剩快速搜索。
 * 当前生效的时间档用 `secondaryContainer` 底色 + 勾号标出来。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopListRangeFabMenu(
    ranges: List<TopListRange>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
            ) {
                Icon(
                    // 展开时换成叉，这是 FAB menu 的惯用反馈
                    imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.FormatListNumbered,
                    contentDescription = stringResource(R.string.toplist),
                )
            }
        },
    ) {
        ranges.forEach { range ->
            val selected = range.id == selectedId
            FloatingActionButtonMenuItem(
                onClick = {
                    expanded = false
                    onSelect(range.id)
                },
                text = { Text(range.label) },
                icon = {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                        )
                    }
                },
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
        }
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

private val SHEET_ROW_HEIGHT = 56.dp

/** 旧实现用 `DownloadManager.getDownloadState(gid) != STATE_INVALID` 判定 */
private val GalleryInfo.downloaded: Boolean
    get() = io.kazusa.ehviewer.download.DownloadManager.getDownloadState(gid) !=
        io.kazusa.ehviewer.dao.DownloadInfo.STATE_INVALID
