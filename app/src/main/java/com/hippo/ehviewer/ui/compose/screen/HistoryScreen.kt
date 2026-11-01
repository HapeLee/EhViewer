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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.dao.HistoryInfo
import kotlin.math.roundToInt

/**
 * 历史记录页的长按动作，具体实现留在 [com.hippo.ehviewer.ui.page.history.HistoryScene] 里
 * （下载管理器、收藏接口、数据库都在那边）。
 *
 * [onMoveLabel] 收到的是标签下标，0 表示默认标签（与旧的 `MoveDialogHelper` 一致）。
 */
class HistoryActions(
    val isDownloaded: (GalleryInfo) -> Boolean,
    val isFavourited: (GalleryInfo) -> Boolean,
    val downloadLabels: () -> List<String>,
    val onRead: (GalleryInfo) -> Unit,
    val onOpenDetail: (GalleryInfo) -> Unit,
    val onDownload: (GalleryInfo) -> Unit,
    val onRemoveDownload: (GalleryInfo) -> Unit,
    val onToggleFavorite: (GalleryInfo) -> Unit,
    val onDelete: (GalleryInfo) -> Unit,
    val onDeleteAll: () -> Unit,
    val onMoveLabel: (GalleryInfo, Int) -> Unit,
)

/**
 * 历史记录页（原 `scene_history.xml` + `HistoryScene`）。
 *
 * 迁移点：
 * - `PagingDataAdapter` + `ViewTransition` 换成 `LazyPagingItems` + 空态分支
 * - `ItemTouchHelper` 左滑删除换成 `SwipeToDismissBox`（只开 EndToStart，与旧的一侧滑动一致）
 * - 长按动作菜单从 `AlertDialog#setAdapter` 换成图标行对话框；分页数据仍靠 Room 失效自动刷新
 * - 下载/收藏角标不是 Compose 状态，靠 [version] 变化重建 [actions] 来触发整表重组
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    title: String,
    items: LazyPagingItems<HistoryInfo>,
    actions: HistoryActions,
    version: Int,
    onOpenDrawer: () -> Unit,
) {
    val currentActions = remember(version) { actions }
    var menuTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var clearAllVisible by remember { mutableStateOf(false) }
    var pendingRemoveDownload by remember { mutableStateOf<GalleryInfo?>(null) }
    var moveTarget by remember { mutableStateOf<GalleryInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_menu_24),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { clearAllVisible = true },
                        enabled = items.itemCount > 0,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.v_clear_all_dark_x24),
                            contentDescription = stringResource(R.string.clear_all),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                items.itemCount > 0 -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.gid },
                        contentType = items.itemContentType { "history" },
                    ) { index ->
                        val info = items[index] ?: return@items
                        SwipeableHistoryItem(
                            info = info,
                            actions = currentActions,
                            onClick = { currentActions.onOpenDetail(info) },
                            onLongClick = { menuTarget = info },
                        )
                    }
                    if (items.loadState.append is LoadState.Loading) {
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
                items.loadState.refresh is LoadState.Loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                )
                else -> EmptyHistory(Modifier.align(Alignment.Center))
            }
        }
    }

    menuTarget?.let { info ->
        HistoryMenuDialog(
            info = info,
            downloaded = currentActions.isDownloaded(info),
            favourited = currentActions.isFavourited(info),
            onDismiss = { menuTarget = null },
            onRead = {
                menuTarget = null
                currentActions.onRead(info)
            },
            onToggleDownload = {
                menuTarget = null
                if (currentActions.isDownloaded(info)) {
                    pendingRemoveDownload = info
                } else {
                    currentActions.onDownload(info)
                }
            },
            onToggleFavorite = {
                menuTarget = null
                currentActions.onToggleFavorite(info)
            },
            onDelete = {
                menuTarget = null
                currentActions.onDelete(info)
            },
            onMoveLabel = {
                menuTarget = null
                moveTarget = info
            },
        )
    }

    pendingRemoveDownload?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingRemoveDownload = null },
            title = { Text(stringResource(R.string.download_remove_dialog_title)) },
            text = {
                Text(stringResource(R.string.download_remove_dialog_message, info.title.orEmpty()))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveDownload = null
                        currentActions.onRemoveDownload(info)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveDownload = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    moveTarget?.let { info ->
        MoveLabelDialog(
            labels = currentActions.downloadLabels(),
            onDismiss = { moveTarget = null },
            onSelect = { index ->
                moveTarget = null
                currentActions.onMoveLabel(info, index)
            },
        )
    }

    if (clearAllVisible) {
        AlertDialog(
            onDismissRequest = { clearAllVisible = false },
            text = { Text(stringResource(R.string.clear_all_history)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearAllVisible = false
                        currentActions.onDeleteAll()
                    },
                ) { Text(stringResource(R.string.clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { clearAllVisible = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableHistoryItem(
    info: HistoryInfo,
    actions: HistoryActions,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                actions.onDelete(info)
            }
            // 由分页数据刷新移除该项，滑动状态直接复位
            false
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.3f },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(R.drawable.v_delete_x24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        HistoryCard(
            info = info,
            downloaded = actions.isDownloaded(info),
            favourited = actions.isFavourited(info),
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun HistoryCard(
    info: HistoryInfo,
    downloaded: Boolean,
    favourited: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val thumbKey = getThumbKey(info.gid)
    val thumbUrl = info.thumbUrl
    val request = remember(thumbKey, thumbUrl) {
        ImageRequest.Builder(context)
            .data(thumbUrl)
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(THUMB_HEIGHT)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(THUMB_HEIGHT)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = EhUtils.getSuitableTitle(info),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = info.uploader.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RatingBar(info.rating)
                CategoryChip(info.category)
            }
            Column(
                modifier = Modifier
                    .height(THUMB_HEIGHT)
                    .padding(start = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (downloaded) {
                        Image(
                            painter = painterResource(R.drawable.v_download_x16),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (favourited) {
                        Image(
                            painter = painterResource(R.drawable.v_heart_x16),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                val language = info.simpleLanguage
                if (!language.isNullOrEmpty()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = info.posted.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RatingBar(rating: Float) {
    val tenths = (rating * 2).roundToInt().coerceIn(0, 10)
    val full = tenths / 2
    val hasHalf = tenths % 2 == 1
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            val icon = when {
                index < full -> R.drawable.v_star_x16
                index == full && hasHalf -> R.drawable.v_star_half_x16
                else -> R.drawable.v_star_outline_x16
            }
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun CategoryChip(category: Int) {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(EhUtils.getCategoryColor(category)))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = EhUtils.getCategory(category).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.big_history),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Text(
            text = stringResource(R.string.history),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HistoryMenuDialog(
    info: GalleryInfo,
    downloaded: Boolean,
    favourited: Boolean,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onToggleDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onMoveLabel: () -> Unit,
) {
    val actions: List<Triple<Int, Int, () -> Unit>> = buildList {
        add(Triple(R.string.read, R.drawable.v_book_open_x24, onRead))
        if (downloaded) {
            add(Triple(R.string.delete_downloads, R.drawable.v_delete_x24, onToggleDownload))
        } else {
            add(Triple(R.string.download, R.drawable.v_download_x24, onToggleDownload))
        }
        add(
            Triple(
                if (favourited) R.string.remove_from_favourites else R.string.add_to_favourites,
                if (favourited) R.drawable.v_heart_broken_x24 else R.drawable.v_heart_x24,
                onToggleFavorite,
            ),
        )
        add(Triple(R.string.delete, R.drawable.v_delete_x24, onDelete))
        if (downloaded) {
            add(
                Triple(
                    R.string.download_move_dialog_title,
                    R.drawable.v_folder_move_x24,
                    onMoveLabel,
                ),
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = EhUtils.getSuitableTitle(info),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                actions.forEach { (label, icon, action) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                action()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun MoveLabelDialog(
    labels: List<String>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_move_dialog_title)) },
        text = {
            Column {
                labels.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private val THUMB_HEIGHT = 112.dp
