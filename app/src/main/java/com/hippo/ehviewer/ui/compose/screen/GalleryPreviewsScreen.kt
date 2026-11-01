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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryPreview
import kotlin.math.max
import kotlin.math.roundToInt

/** 距列表末尾还有这么多项时预取下一页 */
private const val LOAD_MORE_THRESHOLD = 8

/** 缩略图宽高比，对应原 `FixedThumb` 的 `auto:aspect="0.667"` */
private const val THUMB_ASPECT = 0.667f

/**
 * 画廊预览图页（原 `scene_gallery_previews.xml` + `GalleryPreviewsScene`）。
 *
 * 迁移点：
 * - `ContentLayout` + `ContentHelper` 的分页进度不再由 Adapter 驱动，改由外部持有
 *   已加载的页块（`GalleryPreviewsViewModel`），本屏只负责呈现
 * - 列宽仍取 `Settings.previewSize`，用 `BoxWithConstraints` 反推列数，替代
 *   `AutoGridLayoutManager` + `STRATEGY_SUITABLE_SIZE`
 * - 「跳转页码」用 `AlertDialog` + `Slider`，替代 `dialog_go_to.xml` + `GoToDialogHelper`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryPreviewsScreen(
    title: String,
    items: List<GalleryPreview>,
    itemSizePx: Int,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    totalPages: Int,
    topPage: Int,
    onBack: () -> Unit,
    onItemClick: (GalleryPreview) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onTopIndexChange: (Int) -> Unit,
    scrollToIndex: Int?,
    onScrollHandled: () -> Unit,
    onGoToPage: (Int) -> Unit,
) {
    var showGoToDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("\u2190") }
                },
                actions = {
                    if (totalPages > 1) {
                        IconButton(onClick = { showGoToDialog = true }) {
                            Icon(
                                painter = painterResource(R.drawable.v_go_to_dark_x24),
                                contentDescription = stringResource(R.string.go_to),
                            )
                        }
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
                items.isEmpty() && error != null -> ErrorState(error = error, onRetry = onRetry)
                items.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                items.isEmpty() -> Text(
                    text = stringResource(R.string.no_previews),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> PreviewGrid(
                    items = items,
                    itemSizePx = itemSizePx,
                    loadingMore = loadingMore,
                    onItemClick = onItemClick,
                    onLoadMore = onLoadMore,
                    onTopIndexChange = onTopIndexChange,
                    scrollToIndex = scrollToIndex,
                    onScrollHandled = onScrollHandled,
                )
            }
        }
    }

    if (showGoToDialog) {
        GoToPageDialog(
            pages = totalPages,
            initialPage = topPage,
            onDismiss = { showGoToDialog = false },
            onConfirm = { page ->
                showGoToDialog = false
                onGoToPage(page)
            },
        )
    }
}

@Composable
private fun PreviewGrid(
    items: List<GalleryPreview>,
    itemSizePx: Int,
    loadingMore: Boolean,
    onItemClick: (GalleryPreview) -> Unit,
    onLoadMore: () -> Unit,
    onTopIndexChange: (Int) -> Unit,
    scrollToIndex: Int?,
    onScrollHandled: () -> Unit,
) {
    val state = rememberLazyGridState()
    val itemCount by rememberUpdatedState(items.size)
    val loadMore by rememberUpdatedState(onLoadMore)
    val topIndexChanged by rememberUpdatedState(onTopIndexChange)
    val itemSize = with(LocalDensity.current) { itemSizePx.toDp() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 8.dp
        val edge = 16.dp
        val columns = max(1, ((maxWidth - edge * 2 + gap) / (itemSize + gap)).toInt())

        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = edge, end = edge, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.position }) { preview ->
                PreviewCell(preview = preview, onClick = onItemClick)
            }
            if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
        }

        // 触底预取下一页：只在「已经看到接近末尾」时触发，重复调用由 Scene 去重。
        // 键里带 itemCount，是为了在到货后重新评估一次（内容不足一屏时也能继续补齐）
        LaunchedEffect(state, itemCount) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= 0 && last >= itemCount - LOAD_MORE_THRESHOLD) loadMore()
                }
        }

        // 供 Scene 计算「当前顶部是哪一页」，用于跳转对话框的初始值
        LaunchedEffect(state) {
            snapshotFlow { state.firstVisibleItemIndex }.collect { index -> topIndexChanged(index) }
        }

        // 首页定位 / 跳转页码后滚动到目标
        LaunchedEffect(scrollToIndex) {
            val target = scrollToIndex ?: return@LaunchedEffect
            state.scrollToItem(target)
            onScrollHandled()
        }
    }
}

@Composable
private fun PreviewCell(preview: GalleryPreview, onClick: (GalleryPreview) -> Unit) {
    val context = LocalPlatformContext.current
    val imageKey = preview.imageKey
    val imageUrl = preview.imageUrl
    val request = remember(imageKey, imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .apply {
                if (imageKey != null) {
                    memoryCacheKey(imageKey)
                    diskCacheKey(imageKey)
                }
            }
            .crossfade(true)
            .build()
    }

    Column(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_ASPECT)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onClick(preview) },
        )
        Text(
            text = (preview.position + 1).toString(),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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
        TextButton(onClick = onRetry) { Text(stringResource(R.string.refresh)) }
    }
}

/** 替代 `dialog_go_to.xml` + `GoToDialogHelper`：页码从 1 开始展示，回调给 0 基页号 */
@Composable
private fun GoToPageDialog(
    pages: Int,
    initialPage: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val upper = pages.coerceAtLeast(2)
    var page by remember { mutableIntStateOf(initialPage.coerceIn(0, upper - 1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to)) },
        text = {
            Column {
                Text(
                    text = "${page + 1} / $upper",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = (page + 1).toFloat(),
                    onValueChange = { page = it.roundToInt().coerceIn(1, upper) - 1 },
                    valueRange = 1f..upper.toFloat(),
                    steps = (upper - 2).coerceAtLeast(0),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("1", style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.weight(1f))
                    Text("$upper", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(page) }) {
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
