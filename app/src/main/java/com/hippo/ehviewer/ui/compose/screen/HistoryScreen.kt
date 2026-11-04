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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.hippo.ehviewer.ui.compose.component.DialogListItem
import com.hippo.ehviewer.ui.compose.component.DialogListItemSpacing
import com.hippo.ehviewer.ui.compose.component.EhFabAction
import com.hippo.ehviewer.ui.compose.component.EhFabMenu
import com.hippo.ehviewer.ui.compose.component.EhSearchBarPage
import com.hippo.ehviewer.ui.compose.component.SearchHistorySuggestionList
import com.hippo.ehviewer.ui.compose.component.rememberSearchBarScrollState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * - `ItemTouchHelper` 左滑删除换成自绘的左滑手势（右滑要让给左侧抽屉，见 [SwipeableHistoryItem]）
 * - 长按动作菜单从 `AlertDialog#setAdapter` 换成图标行对话框；分页数据仍靠 Room 失效自动刷新
 * - 下载/收藏角标不是 Compose 状态，靠 [version] 变化重建 [actions] 来触发整表重组
 * - 顶栏换成列表页统一的那根 M3 搜索条（[EhSearchBarPage]）+ 右下角 FAB（刷新）：
 *   关键字过滤走 Room 的分页查询，由宿主持有 [keyword] 并重建 PagingSource
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    title: String,
    keyword: String,
    items: LazyPagingItems<HistoryInfo>,
    actions: HistoryActions,
    version: Int,
    onOpenDrawer: () -> Unit,
    onApplySearch: (String) -> Unit,
) {
    val currentActions = remember(version) { actions }
    var menuTarget by remember { mutableStateOf<GalleryInfo?>(null) }
    var clearAllVisible by remember { mutableStateOf(false) }
    var pendingRemoveDownload by remember { mutableStateOf<GalleryInfo?>(null) }
    var moveTarget by remember { mutableStateOf<GalleryInfo?>(null) }

    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(keyword) }
    val scroll = rememberSearchBarScrollState()

    // 搜索条展开时返回键先收起它，而不是退出页面
    BackHandler(enabled = searchExpanded) { searchExpanded = false }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        EhSearchBarPage(
            title = title,
            hint = stringResource(R.string.favorites_search_bar_hint, title),
            query = query,
            expanded = searchExpanded,
            onQueryChange = { query = it },
            onApplySearch = onApplySearch,
            onExpandedChange = {
                searchExpanded = it
                scroll.reset(hideFab = it)
            },
            onOpenDrawer = onOpenDrawer,
            searchBarOffsetY = { scroll.offsetY },
            trailingIcon = {
                IconButton(
                    onClick = { clearAllVisible = true },
                    enabled = items.itemCount > 0,
                ) {
                    Icon(
                        Icons.Filled.ClearAll,
                        contentDescription = stringResource(R.string.clear_all),
                    )
                }
            },
            expandedContent = {
                SearchHistorySuggestionList(
                    query = query,
                    onPick = { picked ->
                        query = picked
                        onApplySearch(picked)
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                when {
                    items.itemCount > 0 -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scroll.connection),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 8.dp,
                        ),
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
                        Modifier
                            .align(Alignment.Center)
                            .padding(padding),
                    )
                    else -> EmptyHistory(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(padding),
                        filtering = keyword.isNotEmpty(),
                    )
                }
            }
        }

        Box(Modifier.navigationBarsPadding()) {
            EhFabMenu(
                visible = !scroll.fabHidden,
                actions = listOf(
                    EhFabAction(Icons.Filled.Refresh, stringResource(R.string.refresh)) {
                        items.refresh()
                    },
                ),
            )
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

/**
 * 卡片与滑动底衬共用的外边距：两处必须一致，
 * 否则底衬会从卡片四周的缝隙里漏出来（历史页整页泛淡红就是这么来的）。
 */
private val HistoryCardHorizontalInset = 8.dp
private val HistoryCardVerticalInset = 4.dp

/** 松手时越过卡片宽度的这个比例就算删除，与旧 `SwipeToDismissBox` 的 `positionalThreshold` 一致 */
private const val HISTORY_DELETE_THRESHOLD = 0.3f

/**
 * 左滑删除，右滑留给左侧抽屉。
 *
 * 没有用 [androidx.compose.material3.SwipeToDismissBox]：它的 `anchoredDraggable` 不区分方向，
 * 只要横向越过 touch slop 就会消费掉事件，右滑于是永远传不到 `DismissibleNavigationDrawer`
 * —— 抽屉的手势挂在整页容器上，只有「没人消费」时才会接管。所以这里自己判方向：
 * 纵向和向右一律不消费，直接放弃这次手势。
 */
@Composable
private fun SwipeableHistoryItem(
    info: HistoryInfo,
    actions: HistoryActions,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val width = remember { mutableFloatStateOf(0f) }
    val offsetX = remember { mutableFloatStateOf(0f) }
    val settleJob = remember { mutableStateOf<Job?>(null) }

    fun settleTo(target: Float) {
        settleJob.value?.cancel()
        settleJob.value = scope.launch {
            animate(
                initialValue = offsetX.floatValue,
                targetValue = target,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) { value, _ -> offsetX.floatValue = value }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { width.floatValue = it.width.toFloat() }
            .pointerInput(info.gid, actions) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 抓住还在回弹的卡片
                    settleJob.value?.cancel()
                    var dragging = false
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break
                        val delta = change.positionChange()
                        if (!dragging) {
                            dx += delta.x
                            dy += delta.y
                            if (abs(dx) <= slop && abs(dy) <= slop) continue
                            // 纵向归 LazyColumn，向右归抽屉：都不消费
                            if (abs(dy) > abs(dx) || dx > 0f) return@awaitEachGesture
                            dragging = true
                        }
                        offsetX.floatValue = (offsetX.floatValue + delta.x).coerceIn(-width.floatValue, 0f)
                        change.consume()
                    }
                    if (dragging) {
                        if (-offsetX.floatValue > width.floatValue * HISTORY_DELETE_THRESHOLD) {
                            actions.onDelete(info)
                        }
                        // 删没删都先弹回去：真删了由分页数据刷新把条目移除
                        settleTo(0f)
                    }
                }
            },
    ) {
        HistoryDeleteBackground(revealPx = { -offsetX.floatValue })
        Box(
            modifier = Modifier.offset {
                IntOffset(offsetX.floatValue.roundToInt(), 0)
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
}

/**
 * 左滑删除的底衬：一张跟着滑动距离「长出来」的红色圆角矩形。
 *
 * 两个不变量：
 * 1. 宽度取卡片已经让出的距离（= 滑动偏移量），且贴右边缘摆放，
 *    所以红底左边缘始终与卡片右边缘重合，看上去就是红卡片从白卡片背后被拉出来；
 * 2. 外层套与卡片相同的圆角裁剪和外边距，红底任何时刻都不会溢出卡片轮廓。
 */
@Composable
private fun BoxScope.HistoryDeleteBackground(revealPx: () -> Float) {
    // 偏移量只在这个作用域里读：每帧只作废底衬自己，不牵动整张卡片
    val density = LocalDensity.current
    val reveal = with(density) { revealPx().coerceAtLeast(0f).toDp() }
    // 图标固定距右边缘 24dp：等红底完全盖住「24dp 图标 + 24dp 留白」之后再淡入，
    // 否则滑动刚起步时会浮在卡片上方
    val iconSpace = 48.dp
    val iconAlpha = ((reveal - iconSpace) / iconSpace).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .matchParentSize()
            .padding(
                horizontal = HistoryCardHorizontalInset,
                vertical = HistoryCardVerticalInset,
            )
            .clip(CardDefaults.shape),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(reveal)
                .background(MaterialTheme.colorScheme.errorContainer),
        )
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .padding(end = 24.dp)
                .alpha(iconAlpha),
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
            .padding(
                horizontal = HistoryCardHorizontalInset,
                vertical = HistoryCardVerticalInset,
            ),
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
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (favourited) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val starTint = colorResource(R.color.yellow_800)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            val icon = when {
                index < full -> Icons.Filled.Star
                index == full && hasHalf -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarOutline
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = starTint,
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
private fun EmptyHistory(
    modifier: Modifier = Modifier,
    filtering: Boolean = false,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.big_history),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // 有关键字时说明「筛完是空的」，别再提示「没有历史」
            text = stringResource(
                if (filtering) R.string.gallery_list_empty_hit else R.string.history,
            ),
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
    val actions: List<Triple<Int, ImageVector, () -> Unit>> = buildList {
        add(Triple(R.string.read, Icons.AutoMirrored.Filled.MenuBook, onRead))
        if (downloaded) {
            add(Triple(R.string.delete_downloads, Icons.Filled.Delete, onToggleDownload))
        } else {
            add(Triple(R.string.download, Icons.Filled.Download, onToggleDownload))
        }
        add(
            Triple(
                if (favourited) R.string.remove_from_favourites else R.string.add_to_favourites,
                if (favourited) Icons.Filled.HeartBroken else Icons.Filled.Favorite,
                onToggleFavorite,
            ),
        )
        add(Triple(R.string.delete, Icons.Filled.Delete, onDelete))
        if (downloaded) {
            add(
                Triple(
                    R.string.download_move_dialog_title,
                    Icons.Filled.DriveFileMove,
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
            Column(verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing)) {
                actions.forEach { (label, icon, action) ->
                    DialogListItem(
                        text = stringResource(label),
                        leadingIcon = icon,
                        onClick = {
                            onDismiss()
                            action()
                        },
                    )
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
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
            ) {
                items(labels.size) { index ->
                    DialogListItem(text = labels[index], onClick = { onSelect(index) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private val THUMB_HEIGHT = 112.dp
