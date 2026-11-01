/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui.page.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.cachedGalleryDetail
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.GalleryPreviewsScreen
import com.hippo.ehviewer.ui.host.ehHostOrNoOp

/**
 * 画廊预览图页。
 *
 * P7-c 出壳：`ContentLayout` / `ContentHelper` 的分页状态改为 [GalleryPreviewsViewModel]
 * 里的「页块」列表，`GalleryPreviewAdapter`、`NotifyDataSetChanged` 和 RecyclerView
 * 生命周期一并消失；`onDestroyView` 里清 `inFlight` 的那个补丁也不需要了。
 *
 * [EhRoute.GalleryPreviews.scrollTo] 的语义保持不变：`-1` 表示详情页已经展示完第一页预览、
 * 从第二页接着看；正数表示在首页内定位到该下标。
 *
 * 点缩略图直接压 [EhRoute.Reader]（R1 起阅读器也进 NavHost，不再起 Activity）。
 */
@Composable
fun GalleryPreviewsScene(route: EhRoute.GalleryPreviews) {
    val context = LocalContext.current
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val vm = viewModel { GalleryPreviewsViewModel(context.applicationContext) }

    LaunchedEffect(vm, route) {
        vm.start(route.scrollTo, cachedGalleryDetail(route))
    }

    LaunchedEffect(vm.tip) {
        val tip = vm.consumeTip()
        if (tip != null) host.showTip(tip, vm.tipLength)
    }

    val itemSizePx = remember { Settings.previewSize }

    GalleryPreviewsScreen(
        title = stringResource(R.string.gallery_previews),
        items = vm.items,
        itemSizePx = itemSizePx,
        loading = vm.loading,
        loadingMore = vm.loadingMore,
        error = vm.error,
        totalPages = vm.totalPages,
        topPage = vm.topPage,
        onBack = { navigator.pop() },
        onItemClick = { preview ->
            val info = vm.gallery ?: return@GalleryPreviewsScreen
            navigator.push(
                EhRoute.Reader(gid = info.gid, token = info.token, page = preview.position),
            )
        },
        onLoadMore = vm::loadNextPage,
        onRetry = vm::retry,
        onTopIndexChange = vm::onTopIndexChange,
        scrollToIndex = vm.scrollToIndex,
        onScrollHandled = vm::onScrollHandled,
        onGoToPage = vm::goToPage,
    )
}
