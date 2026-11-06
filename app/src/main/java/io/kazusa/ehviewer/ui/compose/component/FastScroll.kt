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
package io.kazusa.ehviewer.ui.compose.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 带快速滑动条的 `LazyColumn`。
 *
 * 竖长列表（上千条下载 / 收藏）靠惯性滑动翻到中间某条要划很多下，右侧这条「药丸」
 * 可以直接拖到任意位置。实现来自 komikku 的 `VerticalFastScroller`（legado 也在用同一份），
 * 这里只把配色换成 M3、并去掉了列表里没有的 sticky header 特判。
 *
 * [contentPadding] 的上下内边距会同步到滑块轨道的两端（顶栏 / 导航栏下面不出滑块），
 * 左右内边距**不**跟随：滑块始终贴屏幕右缘。
 */
@Composable
fun FastScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    VerticalFastScroller(
        listState = state,
        modifier = modifier,
        topContentPadding = contentPadding.calculateTopPadding(),
        bottomContentPadding = contentPadding.calculateBottomPadding(),
    ) {
        LazyColumn(
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
    }
}

/**
 * 带快速滑动条的 `LazyVerticalGrid`。
 *
 * 网格版按「行高」估算滚动量（`LazyGridItemInfo` 不保证行高一致），所以行高不齐的网格
 * 会比列表版略不精确——换来的是 1k 条网格也能一把拖到底。
 */
@Composable
fun FastScrollLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    VerticalGridFastScroller(
        state = state,
        columns = columns,
        arrangement = horizontalArrangement,
        contentPadding = contentPadding,
        modifier = modifier,
        topContentPadding = contentPadding.calculateTopPadding(),
        bottomContentPadding = contentPadding.calculateBottomPadding(),
    ) {
        LazyVerticalGrid(
            columns = columns,
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
    }
}

/** 列表版滑块：位置由「已滚过的条目比例」推算，拖动时按比例直接 `scrollToItem` */
@Composable
private fun VerticalFastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.maxOfOrNull { it.height } ?: 0
        val contentWidth = contentPlaceable.maxOfOrNull { it.width } ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty() || layoutInfo.totalItemsCount == 0) return@subcompose

            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()

            // 拖动中、松手后的一小会儿都保持「放大态」：手指挡住了滑块也能看见它在哪
            var recentlyTouched by remember { mutableStateOf(false) }
            LaunchedEffect(isThumbDragged) {
                if (isThumbDragged) {
                    recentlyTouched = true
                } else {
                    delay(THUMB_ACTIVE_DURATION_MILLIS)
                    recentlyTouched = false
                }
            }
            val thumbActive = isThumbDragged || recentlyTouched

            // 列表在滚动或滑块在被拖动时显示；停下 THUMB_HIDE_DELAY_MILLIS 之后淡出
            var capsuleVisible by remember { mutableStateOf(true) }
            LaunchedEffect(isThumbDragged, listState.isScrollInProgress, recentlyTouched) {
                if (isThumbDragged || listState.isScrollInProgress || recentlyTouched) {
                    capsuleVisible = true
                } else {
                    delay(THUMB_HIDE_DELAY_MILLIS)
                    capsuleVisible = false
                }
            }

            // isScrollInProgress 偶尔会抖一下，记一帧上一轮的值把它抹平
            val scrollStateTracker = remember { MutableData(listState.isScrollInProgress) }
            val stableScrollInProgress = scrollStateTracker.value || listState.isScrollInProgress
            scrollStateTracker.value = listState.isScrollInProgress
            val anyScrollInProgress = stableScrollInProgress || isThumbDragged

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            val heightPx = contentHeight.toFloat() -
                thumbTopPadding -
                thumbBottomPadding -
                listState.layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { THUMB_LENGTH.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx
            val scrollHeightPx = contentHeight.toFloat() -
                listState.layoutInfo.beforeContentPadding -
                listState.layoutInfo.afterContentPadding -
                thumbBottomPadding

            val visibleItems = layoutInfo.visibleItemsInfo
            val topItem = visibleItems.firstOrNull { it.bottom >= 0 } ?: visibleItems.first()
            val bottomItem = visibleItems.lastOrNull { it.top <= scrollHeightPx } ?: visibleItems.last()

            val topHiddenProportion = -1f * topItem.top / topItem.size.coerceAtLeast(1)
            val bottomHiddenProportion = (bottomItem.bottom - scrollHeightPx) / bottomItem.size.coerceAtLeast(1)
            val previousSections = topHiddenProportion + topItem.index
            val remainingSections = bottomHiddenProportion + (layoutInfo.totalItemsCount - (bottomItem.index + 1))
            val scrollableSections = previousSections + remainingSections

            val layoutChangeTracker = remember { MutableData(scrollableSections) }
            val layoutChanged = !anyScrollInProgress && abs(layoutChangeTracker.value - scrollableSections) > 0.1
            layoutChangeTracker.value = scrollableSections

            val estimateConfidence = remember { MutableData(remainingSections) }
            if (layoutChanged) estimateConfidence.value = remainingSections
            val maxRemainingSections = remember(estimateConfidence.value) { scrollableSections }
            estimateConfidence.value = max(estimateConfidence.value, remainingSections)

            // 一屏就能装下，不用滑块
            if (maxRemainingSections < 0.5) return@subcompose

            // 拖动滑块：按滑块位置反算目标条目。
            // 用 snapshotFlow 在拖动期间持续收集，而不是 `LaunchedEffect(thumbOffsetY)`——
            // 后者每帧换 key 就会重启一次，滚动还没执行就被下一帧取消，拖动量会整个丢掉
            LaunchedEffect(isThumbDragged) {
                if (layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                if (!isThumbDragged) {
                    return@LaunchedEffect
                }
                snapshotFlow { thumbOffsetY }.collect { offsetY ->
                    // 轨道还没量出来 / 位置是坏的，宁可不滚也别算出脏 index
                    if (!offsetY.isFinite() || trackHeightPx <= 0f) return@collect
                    val thumbProportion = (offsetY - thumbTopPadding) / trackHeightPx
                    if (thumbProportion <= 0.001f) {
                        estimateConfidence.value = -1f
                        listState.scrollToItem(index = 0, scrollOffset = 0)
                        return@collect
                    }
                    val scrollRemainingSections = (1f - thumbProportion) * maxRemainingSections
                    val currentSection = layoutInfo.totalItemsCount - scrollRemainingSections
                    val scrollSectionIndex = currentSection.toInt().coerceAtMost(layoutInfo.totalItemsCount)
                    val expectedScrollItem = visibleItems.find { it.index == scrollSectionIndex } ?: visibleItems.first()
                    val scrollRelativeOffset = expectedScrollItem.size * (currentSection - scrollSectionIndex)
                    val scrollSectionOffset = (scrollRelativeOffset - scrollHeightPx).roundToInt()
                    val scrollItemIndex = scrollSectionIndex.coerceIn(0, layoutInfo.totalItemsCount - 1)
                    val scrollItemOffset = scrollSectionOffset + (scrollSectionIndex - scrollItemIndex) * bottomItem.size
                    listState.scrollToItem(index = scrollItemIndex, scrollOffset = scrollItemOffset)
                }
            }

            // 列表自己滚动：滑块跟着走
            if (layoutInfo.totalItemsCount != 0 && !isThumbDragged) {
                val proportion = (1f - remainingSections / maxRemainingSections).coerceIn(0f, 1f)
                thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            }

            Thumb(
                offsetY = thumbOffsetY,
                onDragDelta = { delta ->
                    thumbOffsetY = (thumbOffsetY + delta).coerceIn(
                        thumbTopPadding,
                        (thumbTopPadding + trackHeightPx).coerceAtLeast(thumbTopPadding),
                    )
                },
                thumbActive = thumbActive,
                thumbDraggable = !listState.isScrollInProgress && capsuleVisible,
                isThumbDragged = isThumbDragged,
                capsuleVisible = capsuleVisible,
                dragInteractionSource = dragInteractionSource,
                thumbColor = thumbColor,
                endContentPadding = endContentPadding,
            )
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.maxOfOrNull { it.width } ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.forEach {
                it.place(0, 0)
            }
            scrollerPlaceable.forEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

/** 网格版滑块：位置由「行号比例」推算，行高按可视区域的平均值估 */
@Composable
private fun VerticalGridFastScroller(
    state: LazyGridState,
    columns: GridCells,
    arrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val slotSizesSums = rememberColumnWidthSums(
        columns = columns,
        horizontalArrangement = arrangement,
        contentPadding = contentPadding,
    )

    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.maxOfOrNull { it.height } ?: 0
        val contentWidth = contentPlaceable.maxOfOrNull { it.width } ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = state.layoutInfo
            val showScroller = layoutInfo.visibleItemsInfo.size < layoutInfo.totalItemsCount
            if (!showScroller) return@subcompose
            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()

            var recentlyTouched by remember { mutableStateOf(false) }
            LaunchedEffect(isThumbDragged) {
                if (isThumbDragged) {
                    recentlyTouched = true
                } else {
                    delay(THUMB_ACTIVE_DURATION_MILLIS)
                    recentlyTouched = false
                }
            }
            val thumbActive = isThumbDragged || recentlyTouched

            var capsuleVisible by remember { mutableStateOf(true) }
            LaunchedEffect(isThumbDragged, state.isScrollInProgress, recentlyTouched) {
                if (isThumbDragged || state.isScrollInProgress || recentlyTouched) {
                    capsuleVisible = true
                } else {
                    delay(THUMB_HIDE_DELAY_MILLIS)
                    capsuleVisible = false
                }
            }

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            val heightPx = contentHeight.toFloat() -
                thumbTopPadding -
                thumbBottomPadding -
                state.layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { THUMB_LENGTH.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx

            val columnCount = remember(columns) { slotSizesSums(constraints).size.coerceAtLeast(1) }
            val scrollRange = remember(columns) { computeGridScrollRange(state = state, columnCount = columnCount) }

            // 拖动滑块：换算成行号再 scrollToItem。理由同上：拖动期间持续收集，
            // 不用 `LaunchedEffect(thumbOffsetY)`，避免每帧重启把拖动量丢掉
            LaunchedEffect(isThumbDragged) {
                if (layoutInfo.totalItemsCount == 0) return@LaunchedEffect
                if (!isThumbDragged) {
                    return@LaunchedEffect
                }
                snapshotFlow { thumbOffsetY }.collect { offsetY ->
                    // 轨道还没量出来 / 位置是坏的，宁可不滚也别算出脏 index
                    if (!offsetY.isFinite() || trackHeightPx <= 0f) return@collect
                    val layoutInfo = state.layoutInfo
                    val startChild = layoutInfo.visibleItemsInfo.firstOrNull() ?: return@collect
                    val endChild = layoutInfo.visibleItemsInfo.last()
                    val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
                    val laidOutRows = 1 + abs(endChild.index - startChild.index) / columnCount
                    val avgSizePerRow = laidOutArea.toFloat() / laidOutRows
                    if (avgSizePerRow <= 0f) return@collect

                    val scrollRatio = (offsetY - thumbTopPadding) / trackHeightPx
                    val scrollAmt = scrollRatio * (scrollRange.toFloat() - heightPx).coerceAtLeast(1f)
                    if (!scrollAmt.isFinite()) return@collect
                    val rowNumber = (scrollAmt / avgSizePerRow).toInt().coerceAtLeast(0)
                    val rowOffset = scrollAmt - rowNumber * avgSizePerRow

                    // 行号换回 index 一定要卡在条目范围内：翻页时 totalItemsCount 会变，
                    // 估算出来的行号可能越界（越界 index 是这类滚动条最常见的崩法）
                    val targetIndex = (columnCount.toLong() * rowNumber)
                        .coerceIn(0L, (layoutInfo.totalItemsCount - 1).toLong())
                        .toInt()
                    state.scrollToItem(index = targetIndex, scrollOffset = rowOffset.roundToInt())
                }
            }

            // 列表自己滚动：滑块跟着走
            LaunchedEffect(state.firstVisibleItemScrollOffset) {
                if (state.layoutInfo.totalItemsCount == 0 || isThumbDragged) return@LaunchedEffect
                val scrollOffset = computeGridScrollOffset(state = state, columnCount = columnCount)
                // 网格条目高度是估的，比例上限卡在 1，避免滑块被推出轨道
                val extraScrollRange = (scrollRange.toFloat() - heightPx).coerceAtLeast(1f)
                val proportion = (scrollOffset.toFloat() / extraScrollRange).coerceIn(0f, 1f)
                thumbOffsetY = trackHeightPx * proportion + thumbTopPadding
            }

            Thumb(
                offsetY = thumbOffsetY,
                onDragDelta = { delta ->
                    thumbOffsetY = (thumbOffsetY + delta).coerceIn(
                        thumbTopPadding,
                        (thumbTopPadding + trackHeightPx).coerceAtLeast(thumbTopPadding),
                    )
                },
                thumbActive = thumbActive,
                thumbDraggable = !state.isScrollInProgress && capsuleVisible,
                isThumbDragged = isThumbDragged,
                capsuleVisible = capsuleVisible,
                dragInteractionSource = dragInteractionSource,
                thumbColor = thumbColor,
                endContentPadding = endContentPadding,
            )
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.maxOfOrNull { it.width } ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.forEach {
                it.place(0, 0)
            }
            scrollerPlaceable.forEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

/**
 * 滑块本体：静止时是一条 4dp 的半透明细条，按住后涨成 12dp 的实色药丸。
 *
 * 拖动逻辑放在这里（列表 / 网格两版共用），位置换算留在各自的 `LaunchedEffect` 里。
 */
@Composable
private fun Thumb(
    offsetY: Float,
    onDragDelta: (Float) -> Unit,
    thumbActive: Boolean,
    thumbDraggable: Boolean,
    isThumbDragged: Boolean,
    capsuleVisible: Boolean,
    dragInteractionSource: MutableInteractionSource,
    thumbColor: Color,
    endContentPadding: Dp,
) {
    val idleThumbColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = IDLE_THUMB_ALPHA)
    val thumbFormProgress by animateFloatAsState(
        targetValue = if (thumbActive) 1f else 0f,
        animationSpec = tween(durationMillis = THUMB_FORM_DURATION_MILLIS),
        label = "thumbForm",
    )
    val animatedThumbColor by animateColorAsState(
        targetValue = if (thumbActive) thumbColor else idleThumbColor,
        animationSpec = tween(durationMillis = THUMB_FORM_DURATION_MILLIS),
        label = "thumbColor",
    )
    val thumbVisibilityAlpha by animateFloatAsState(
        targetValue = if (capsuleVisible) 1f else 0f,
        animationSpec = tween(durationMillis = THUMB_HIDE_FADE_MILLIS),
        label = "thumbVisibility",
    )
    val thumbWidth = IDLE_THUMB_THICKNESS + (THUMB_THICKNESS - IDLE_THUMB_THICKNESS) * thumbFormProgress
    val thumbHeight = IDLE_THUMB_LENGTH + (THUMB_LENGTH - IDLE_THUMB_LENGTH) * thumbFormProgress

    Box(
        modifier = Modifier
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .then(
                // 拖得住的时候才挂 draggable，省掉常态下的一层 pointer input
                if (thumbDraggable) {
                    Modifier.draggable(
                        interactionSource = dragInteractionSource,
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> onDragDelta(delta) },
                    )
                } else {
                    Modifier
                },
            )
            .then(
                // 只在「可拖但没在拖」时把滑块划进系统手势排除区，免得贴边时抢返回手势
                if (thumbDraggable && !isThumbDragged) {
                    Modifier.systemGestureExclusion()
                } else {
                    Modifier
                },
            )
            .height(THUMB_LENGTH)
            .padding(start = 8.dp, end = 4.dp)
            .padding(end = endContentPadding)
            .width(THUMB_THICKNESS),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // 宽度决定圆角：任何尺寸下两端都是正半圆
        Box(
            modifier = Modifier
                .size(thumbWidth, thumbHeight)
                .alpha(thumbVisibilityAlpha)
                .background(
                    color = animatedThumbColor,
                    shape = RoundedCornerShape(thumbWidth / 2),
                ),
        )
    }
}

/** 网格每一列的右边缘累计宽度，用来反推列数 */
@Composable
private fun rememberColumnWidthSums(
    columns: GridCells,
    horizontalArrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
) = remember<Density.(Constraints) -> List<Int>>(
    columns,
    horizontalArrangement,
    contentPadding,
) {
    { constraints ->
        require(constraints.maxWidth != Constraints.Infinity) {
            "LazyVerticalGrid's width should be bound by parent"
        }
        val horizontalPadding = contentPadding.calculateStartPadding(LayoutDirection.Ltr) +
            contentPadding.calculateEndPadding(LayoutDirection.Ltr)
        val gridWidth = constraints.maxWidth - horizontalPadding.roundToPx()
        with(columns) {
            calculateCrossAxisCellSizes(
                gridWidth,
                horizontalArrangement.spacing.roundToPx(),
            ).toMutableList().apply {
                for (i in 1..<size) {
                    this[i] += this[i - 1]
                }
            }
        }
    }
}

/**
 * 网格当前滚动量（像素）。估算要用到可视条目，所以这里一律走 `firstOrNull`：
 * 布局还没跑完、或者一帧内条目被换掉时 `visibleItemsInfo` 可能是空的，
 * 直接 `first()` 会在滚动/拖动路径上抛 `NoSuchElementException`
 */
private fun computeGridScrollOffset(state: LazyGridState, columnCount: Int): Int {
    val layoutInfo = state.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return 0
    val startChild = layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0
    val endChild = layoutInfo.visibleItemsInfo.last()
    val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
    val laidOutRows = 1 + abs(endChild.index - startChild.index) / columnCount
    val avgSizePerRow = laidOutArea.toFloat() / laidOutRows
    if (!avgSizePerRow.isFinite()) return 0

    val rowsBefore = min(startChild.index, endChild.index).coerceAtLeast(0) / columnCount
    return (rowsBefore * avgSizePerRow - startChild.offset.y).roundToInt()
}

/** 网格内容总高度（像素），同上：可视条目为空时给 0，宁可滑块不动也不抛 */
private fun computeGridScrollRange(state: LazyGridState, columnCount: Int): Int {
    val layoutInfo = state.layoutInfo
    if (layoutInfo.totalItemsCount == 0) return 0
    val startChild = layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0
    val endChild = layoutInfo.visibleItemsInfo.last()
    val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
    val laidOutRows = 1 + abs(endChild.index - startChild.index) / columnCount
    val avgSizePerRow = laidOutArea.toFloat() / laidOutRows
    if (!avgSizePerRow.isFinite()) return 0

    val totalRows = 1 + (layoutInfo.totalItemsCount - 1) / columnCount
    val endSpacing = avgSizePerRow - endChild.size.height
    return (endSpacing + avgSizePerRow * totalRows).roundToInt()
}

/** 一帧之外的「上一轮值」：SubcomposeLayout 的 measure 里不能直接写状态，用它绕开 */
private class MutableData<T>(var value: T)

private val THUMB_LENGTH = 48.dp
private val THUMB_THICKNESS = 12.dp
private val IDLE_THUMB_LENGTH = 36.dp
private val IDLE_THUMB_THICKNESS = 4.dp
private const val IDLE_THUMB_ALPHA = 0.8f
private const val THUMB_FORM_DURATION_MILLIS = 250
private const val THUMB_ACTIVE_DURATION_MILLIS = 3000L
private const val THUMB_HIDE_DELAY_MILLIS = 3000L
private const val THUMB_HIDE_FADE_MILLIS = 250

private val LazyListItemInfo.top: Int
    get() = offset

private val LazyListItemInfo.bottom: Int
    get() = offset + size
