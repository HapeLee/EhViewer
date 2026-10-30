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

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.ui.compose.component.GalleryItem
import com.hippo.ehviewer.ui.compose.component.GalleryItemLayout
import com.hippo.ehviewer.ui.compose.component.GridCellMinWidth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 收藏抽屉的一行：`favCat` 取 `FavListUrlBuilder.FAV_CAT_*` 或 0..9 */
data class FavCategoryItem(
    val favCat: Int,
    val name: String,
    val count: Int,
)

/**
 * 收藏页（原 `scene_favorites.xml` + `FavoritesScene`）。
 *
 * 结构调整：
 * - 分类列表从「宿主 DrawerLayout 的 END 抽屉」搬进场景内的 [ModalNavigationDrawer]，
 *   只允许点图标打开（`gesturesEnabled = false`），避免和宿主的左侧导航抽屉抢手势
 * - `SearchBar` 的两态换成顶栏的「标题态 / 输入态」
 * - 8 个 FAB 折叠成「刷新 FAB + 溢出菜单（跳到日期 / 最后一页）」，
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val selectionMode = selection.isNotEmpty()

    var overflowVisible by remember { mutableStateOf(false) }
    var query by remember(appliedKeyword) { mutableStateOf(appliedKeyword) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var defaultSlotDialog by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.collections),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { defaultSlotDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.v_heart_box_dark_x24),
                            contentDescription = stringResource(R.string.default_favorites_collection),
                        )
                    }
                }
                LazyColumn {
                    items(categories, key = { it.favCat }) { category ->
                        DrawerCategoryRow(
                            category = category,
                            selected = category.favCat == currentFavCat,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onSelectCategory(category.favCat)
                            },
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                when {
                    searchMode -> SearchTopBar(
                        hint = searchHint,
                        query = query,
                        onQueryChange = { query = it },
                        onBack = onExitSearch,
                        onSubmit = { onApplySearch(query) },
                    )
                    selectionMode -> SelectionTopBar(
                        count = selection.size,
                        onClose = onClearSelection,
                        onSelectAll = onSelectAll,
                        onDownload = onDownloadSelected,
                        onDelete = { deleteConfirm = true },
                        onMove = { moveDialog = true },
                    )
                    else -> TopAppBar(
                        title = { Text(title) },
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
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    painter = painterResource(R.drawable.v_heart_box_dark_x24),
                                    contentDescription = stringResource(R.string.collections),
                                )
                            }
                            Box {
                                IconButton(onClick = { overflowVisible = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.v_more_vert_dark_x24),
                                        contentDescription = null,
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflowVisible,
                                    onDismissRequest = { overflowVisible = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.refresh)) },
                                        onClick = {
                                            overflowVisible = false
                                            onRefresh()
                                        },
                                    )
                                    if (currentFavCat != FavListUrlBuilder.FAV_CAT_LOCAL) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.go_to)) },
                                            onClick = {
                                                overflowVisible = false
                                                onGoToDate()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.go_to_last_page)) },
                                            onClick = {
                                                overflowVisible = false
                                                onGoToLastPage()
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (!selectionMode && !searchMode) {
                    FloatingActionButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(R.drawable.v_refresh_dark_x24),
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
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
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
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
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
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
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
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
                Column {
                    targets.forEachIndexed { index, target ->
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        moveDialog = false
                                        onMoveSelected(index)
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
                Column {
                    options.forEachIndexed { index, label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        defaultSlotDialog = false
                                        onSetDefaultSlot(index)
                                    },
                                    onLongClick = {},
                                )
                                .padding(vertical = 12.dp),
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
            IconButton(onClick = onBack) { Text("\u2190") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Text("\u2715") }
        },
        title = { Text(count.toString()) },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    painter = painterResource(R.drawable.v_check_all_dark_x24),
                    contentDescription = null,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    painter = painterResource(R.drawable.v_download_x24),
                    contentDescription = stringResource(R.string.download),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.v_delete_x24),
                    contentDescription = stringResource(R.string.delete),
                )
            }
            IconButton(onClick = onMove) {
                Icon(
                    painter = painterResource(R.drawable.v_folder_move_x24),
                    contentDescription = stringResource(R.string.move_favorites_dialog_title),
                )
            }
        },
    )
}

@Composable
private fun DrawerCategoryRow(
    category: FavCategoryItem,
    selected: Boolean,
    onClick: () -> Unit,
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
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
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

private val CARD_THUMB_HEIGHT = 112.dp
