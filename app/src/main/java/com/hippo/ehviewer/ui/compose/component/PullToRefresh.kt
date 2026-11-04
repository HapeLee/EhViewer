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
package com.hippo.ehviewer.ui.compose.component

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 页面级下拉刷新容器：M3 `PullToRefreshBox` + M3 Expressive 的 `LoadingIndicator`。
 *
 * 包一层的原因有两个：
 * - 指示器要跟手势联动，必须和 `PullToRefreshBox` 用同一个 `PullToRefreshState`，
 *   每个页面各写一遍很容易漏掉 `state` 的传递（漏了就变成拉不动指示器）；
 * - `PullToRefreshDefaults.LoadingIndicator` 带 `@ExperimentalMaterial3ExpressiveApi`，
 *   收在这里以后 opt-in 只在这个文件加一次，调用点不必逐个 `@OptIn`。
 *
 * [content] 里得放可滚动的容器（`LazyColumn` / `LazyVerticalGrid` / 带 `verticalScroll` 的
 * `Column`）——手势靠它们的 nested scroll 驱动；纯静态内容拉不动。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EhPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            // M3E 的 LoadingIndicator：下拉时按 distanceFraction 变形旋转，刷新中变成
            // 无限循环的 shape morph；视觉上比默认的圆形箭头更"有生命力"
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        enabled = enabled,
        content = content,
    )
}
