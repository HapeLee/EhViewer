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

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.data.FavListUrlBuilder
import io.kazusa.ehviewer.client.data.GalleryInfo
import io.kazusa.ehviewer.ui.compose.component.DialogListItem
import io.kazusa.ehviewer.ui.compose.component.DialogListItemSpacing
import io.kazusa.ehviewer.ui.compose.component.EhFabAction
import io.kazusa.ehviewer.ui.compose.component.EhFabMenu
import io.kazusa.ehviewer.ui.compose.component.EhPullToRefreshBox
import io.kazusa.ehviewer.ui.compose.component.EhSearchBarPage
import io.kazusa.ehviewer.ui.compose.component.GalleryItem
import io.kazusa.ehviewer.ui.compose.component.GalleryItemLayout
import io.kazusa.ehviewer.ui.compose.component.GridCellMinWidth
import io.kazusa.ehviewer.ui.compose.component.ListItemShape
import io.kazusa.ehviewer.ui.compose.component.SearchHistorySuggestionList
import io.kazusa.ehviewer.ui.compose.component.rememberSearchBarScrollState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 收藏夹选择面板的一行：`favCat` 取 `FavListUrlBuilder.FAV_CAT_*` 或 0..9 */
data class FavCategoryItem(
    val favCat: Int,
    val name: String,
    val count: Int,
)

/**
 * 收藏页（原 `scene_favorites.xml` + `FavoritesScene`）。
 *
 * 结构调整：
 * - 分类列表从「宿主 DrawerLayout 的 END 抽屉」改成普通 bottom sheet（见 [FavoritesCategorySheet]）：
 *   抽屉形态想要避开宿主左侧抽屉的手势就得关掉 `gesturesEnabled`，代价是连关闭手势一起没了
 * - 顶栏换成列表页统一的那根 M3 搜索条（[EhSearchBarPage]，与画廊列表页同构）：
 *   收起时就是页面标题 + 抽屉按钮 + 分类入口，点开就地长成 docked 表搜收藏
 * - 刷新 / 跳到日期 / 末页折进右下角的 FAB 菜单（[EhFabMenu]），与画廊列表页一致；
 *   多选态下顶栏换成上下文菜单（全选 / 下载 / 删除 / 移动）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    title: String,
    searchHint: String,
    items: List<GalleryInfo>,
    listMode: Int,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    searchMode: Boolean,
    appliedKeyword: String,
    categories: List<FavCategoryItem>,
    currentFavCat: Int,
    selection: Set<Long>,
    onOpenNavDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (GalleryInfo) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onMoveSelected: (Int) -> Unit,
    onEnterSearch: () -> Unit,
    onExitSearch: () -> Unit,
    onApplySearch: (String) -> Unit,
    onSelectCategory: (Int) -> Unit,
    onSetDefaultSlot: (Int) -> Unit,
    onGoToDate: () -> Unit,
    onGoToLastPage: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 1.5 里 rememberModalBottomSheetState 已废弃，新的这版必须显式传初始值 Hidden
    val sheetState = rememberBottomSheetState(SheetValue.Hidden)
    var sheetVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val selectionMode = selection.isNotEmpty()
    // 搜索条收缩位移 + FAB 显隐：与画廊列表页共用同一套状态机
    val scroll = rememberSearchBarScrollState()

    var query by remember(appliedKeyword) { mutableStateOf(appliedKeyword) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var defaultSlotDialog by remember { mutableStateOf(false) }

    val content: @Composable (PaddingValues) -> Unit = { padding ->
        FavoritesListContent(
            items = items,
            listMode = listMode,
            loading = loading,
            loadingMore = loadingMore,
            error = error,
            selectionMode = selectionMode,
            selection = selection,
            padding = padding,
            listState = listState,
            gridState = gridState,
            scrollConnection = scroll.connection,
            onRefresh = onRefresh,
            onOpenDetail = onOpenDetail,
            onToggleSelect = onToggleSelect,
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (selectionMode) {
            // 多选态仍用上下文顶栏：这时搜索条没有意义，先让位给「已选 / 全选 / 下载 / 删除 / 移动」
            val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
            Scaffold(
                modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
                topBar = {
                    SelectionTopBar(
                        count = selection.size,
                        onClose = onClearSelection,
                        onSelectAll = onSelectAll,
                        onDownload = onDownloadSelected,
                        onDelete = { deleteConfirm = true },
                        onMove = { moveDialog = true },
                        scrollBehavior = behavior,
                    )
                },
            ) { padding -> content(padding) }
        } else {
            EhSearchBarPage(
                title = title,
                hint = searchHint,
                query = query,
                expanded = searchMode,
                onQueryChange = { query = it },
                onApplySearch = onApplySearch,
                onExpandedChange = { expanded ->
                    if (expanded) onEnterSearch() else onExitSearch()
                    scroll.reset(hideFab = expanded)
                },
                onOpenDrawer = onOpenNavDrawer,
                searchBarOffsetY = { scroll.offsetY },
                trailingIcon = {
                    IconButton(onClick = { sheetVisible = true }) {
                        Icon(
                            Icons.Filled.FavoriteBorder,
                            contentDescription = stringResource(R.string.collections),
                        )
                    }
                },
                expandedContent = {
                    SearchHistorySuggestionList(
                        query = query,
                        onPick = { keyword ->
                            query = keyword
                            onApplySearch(keyword)
                        },
                    )
                },
            ) { padding -> content(padding) }
        }

        if (!selectionMode) {
            val refreshLabel = stringResource(R.string.refresh)
            val goToLabel = stringResource(R.string.go_to)
            val lastPageLabel = stringResource(R.string.go_to_last_page)
            // 本地收藏是本地库过滤出来的，没有「日期 / 末页」这种分页概念
            val cloud = currentFavCat != FavListUrlBuilder.FAV_CAT_LOCAL
            Box(Modifier.navigationBarsPadding()) {
                EhFabMenu(
                    visible = !scroll.fabHidden,
                    actions = buildList {
                        if (cloud) {
                            add(EhFabAction(Icons.Filled.DateRange, goToLabel, onGoToDate))
                            add(
                                EhFabAction(
                                    Icons.AutoMirrored.Filled.LastPage,
                                    lastPageLabel,
                                    onGoToLastPage,
                                ),
                            )
                        }
                        add(EhFabAction(Icons.Filled.Refresh, refreshLabel, onRefresh))
                    },
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
            FavoritesCategorySheet(
                categories = categories,
                currentFavCat = currentFavCat,
                onSelect = { favCat ->
                    onSelectCategory(favCat)
                    hideSheet()
                },
                onSetDefaultSlot = { defaultSlotDialog = true },
            )
        }
    }

    // 触底加载
    LaunchedEffect(listState, gridState, listMode, items.size, hasMore) {
        if (!hasMore) return@LaunchedEffect
        val flow: Flow<Int> = if (listMode == GalleryItemLayout.GRID) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
        } else {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
        }
        flow.collect { last -> if (last >= 0 && last >= items.size - 5) onLoadMore() }
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(R.string.delete_favorites_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.delete_favorites_dialog_message, selection.size),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirm = false
                        onDeleteSelected()
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (moveDialog) {
        val targets = categories.filter {
            it.favCat == FavListUrlBuilder.FAV_CAT_LOCAL || it.favCat in 0..9
        }
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text(stringResource(R.string.move_favorites_dialog_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
                ) {
                    items(targets.size) { index ->
                        DialogListItem(
                            text = targets[index].name,
                            onClick = {
                                moveDialog = false
                                onMoveSelected(index)
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

    if (defaultSlotDialog) {
        val options = buildList {
            add(stringResource(R.string.let_me_select))
            add(stringResource(R.string.local_favorites))
            categories.filter { it.favCat in 0..9 }.forEach { add(it.name) }
        }
        AlertDialog(
            onDismissRequest = { defaultSlotDialog = false },
            title = { Text(stringResource(R.string.default_favorites_collection)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
                ) {
                    items(options.size) { index ->
                        DialogListItem(
                            text = options[index],
                            onClick = {
                                defaultSlotDialog = false
                                onSetDefaultSlot(index)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { defaultSlotDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * 收藏列表正文：列表 / 网格共用，多选与普通两种顶栏外壳都调它。
 *
 * 内容按 `padding` 内缩、列表本身铺满窗口：滚动时条目从搜索条 / 导航栏底下穿过去，
 * 搜索条才收得上去（位移由 [scrollConnection] 统计）。
 */
@Composable
private fun FavoritesListContent(
    items: List<GalleryInfo>,
    listMode: Int,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    selectionMode: Boolean,
    selection: Set<Long>,
    padding: PaddingValues,
    listState: LazyListState,
    gridState: LazyGridState,
    scrollConnection: NestedScrollConnection,
    onRefresh: () -> Unit,
    onOpenDetail: (GalleryInfo) -> Unit,
    onToggleSelect: (Long) -> Unit,
) {
    EhPullToRefreshBox(
        isRefreshing = loading && items.isNotEmpty(),
        onRefresh = onRefresh,
        // 搜索态下面仍是收藏列表，只有多选态不该被下拉手势打扰
        enabled = !selectionMode,
        modifier = Modifier.fillMaxSize(),
    ) {
        val contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 8.dp,
        )
        when {
            items.isEmpty() && error != null -> ErrorState(error = error, onRetry = onRefresh)
            items.isEmpty() && loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
            )
            items.isEmpty() -> Text(
                text = stringResource(R.string.gallery_list_empty_hit),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            else -> if (listMode == GalleryItemLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(GridCellMinWidth),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    contentPadding = contentPadding,
                ) {
                    gridItems(items = items, key = { it.gid }) { info ->
                        GalleryItem(
                            info = info,
                            layout = GalleryItemLayout.GRID,
                            selected = info.gid in selection,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) onToggleSelect(info.gid) else onOpenDetail(info)
                            },
                            onLongClick = { onToggleSelect(info.gid) },
                        )
                    }
                    if (loadingMore) {
                        item { LoadingMoreIndicator() }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    contentPadding = contentPadding,
                ) {
                    items(items.size, key = { items[it].gid }) { index ->
                        val info = items[index]
                        GalleryItem(
                            info = info,
                            layout = GalleryItemLayout.LIST,
                            selected = info.gid in selection,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) onToggleSelect(info.gid) else onOpenDetail(info)
                            },
                            onLongClick = { onToggleSelect(info.gid) },
                        )
                    }
                    if (loadingMore) {
                        item { LoadingMoreIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingMoreIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeTopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        },
        title = { Text(count.toString()) },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = null,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = stringResource(R.string.download),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                )
            }
            IconButton(onClick = onMove) {
                Icon(
                    Icons.Filled.DriveFileMove,
                    contentDescription = stringResource(R.string.move_favorites_dialog_title),
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/**
 * 收藏夹选择面板。
 *
 * 之前是页面内的 `ModalNavigationDrawer` + `ModalDrawerSheet`：为了不和宿主左侧导航抽屉
 * 抢水平手势，它被设成 `gesturesEnabled = false`，而这一下同时废掉了「滑动关闭」和
 * 「点遮罩关闭」，抽屉又没注册返回键 —— 打开后唯一的出口是「点中某个分类」，
 * 点错了也只能随便选一个逃出去。换成 bottom sheet 后，下滑 / 点遮罩 / 返回键三条路都通。
 */
@Composable
private fun FavoritesCategorySheet(
    categories: List<FavCategoryItem>,
    currentFavCat: Int,
    onSelect: (Int) -> Unit,
    onSetDefaultSlot: () -> Unit,
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
                text = stringResource(R.string.collections),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSetDefaultSlot) {
                Icon(
                    imageVector = Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(R.string.default_favorites_collection),
                )
            }
        }
        // 分类就十来个，用普通 Column 让 sheet 高度跟着内容走；
        // 换成 LazyColumn 会因为「有界高度必然铺满」把面板顶到整屏
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
        ) {
            categories.forEach { category ->
                CategoryRow(
                    category = category,
                    selected = category.favCat == currentFavCat,
                    onClick = { onSelect(category.favCat) },
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: FavCategoryItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // sheet 的行沿用圆角块：sheet 容器本身是 surfaceContainerLow，行取高一档才看得出形状
    Surface(
        shape = ListItemShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {}),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else null,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = category.count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.refresh)) }
    }
}
