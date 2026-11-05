/*
 * Copyright 2016 Hippo Seven
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
package io.kazusa.ehviewer.ui.page.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.UrlOpener
import io.kazusa.ehviewer.client.data.ListUrlBuilder
import io.kazusa.ehviewer.support.util.addTextToClipboard
import io.kazusa.ehviewer.support.util.toBBCode
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.cachedGalleryDetail
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.nav.galleryQueryOf
import io.kazusa.ehviewer.ui.compose.screen.GalleryCommentsScreen
import io.kazusa.ehviewer.ui.compose.text.toSpannable
import io.kazusa.ehviewer.ui.host.ehHostOrNoOp

/**
 * 画廊评论页。
 *
 * P7-c 出壳：`CommentAdapter` / `ViewTransition` / `SwipeRefreshLayout` 早已由 Compose
 * 列表接管，这一片把最后剩下的 Fragment 部分——状态、请求、`onBackPressed`、
 * `onSaveInstanceState`——搬进 [GalleryCommentsViewModel]。
 *
 * 评论变更仍然只写 `GalleryCommentsStore`，详情页回前台时自己读，不走 `setResult` 回传。
 */
@Composable
fun GalleryCommentsScene(route: EhRoute.GalleryComments) {
    val context = LocalContext.current
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val vm = viewModel { GalleryCommentsViewModel(context.applicationContext) }

    LaunchedEffect(vm, route) { vm.start(route, cachedGalleryDetail(route)) }

    LaunchedEffect(vm.tip) {
        val tip = vm.consumeTip()
        if (tip != null) host.showTip(tip, vm.tipLength)
    }

    // 评论框关闭后收键盘：初值 0 不触发，只有真正关过才发请求
    LaunchedEffect(vm.hideKeyboardRequests) {
        if (vm.hideKeyboardRequests > 0) host.hideSoftInput()
    }

    // 返回键先关评论框，再退页面（与旧 onBackPressed 一致）
    BackHandler(enabled = vm.composerVisible) { vm.dismissComposer() }

    GalleryCommentsScreen(
        title = stringResource(R.string.gallery_comments),
        comments = vm.comments,
        hasMore = vm.hasMore,
        loadingMore = vm.loadingMore,
        refreshing = vm.refreshing,
        composerVisible = vm.composerVisible,
        editingId = vm.editingId,
        posting = vm.posting,
        onBack = {
            if (vm.composerVisible) vm.dismissComposer() else navigator.pop()
        },
        onRefresh = vm::refresh,
        onLoadMore = vm::loadMore,
        onNewComment = vm::newComment,
        onEditComment = vm::editComment,
        onVote = vm::vote,
        onBlockCommenter = vm::blockCommenter,
        onCopyComment = { text -> context.addTextToClipboard(text, false) },
        onOpenUploader = { user ->
            navigator.push(
                EhRoute.GalleryList(
                    galleryQueryOf(
                        ListUrlBuilder().apply {
                            mode = ListUrlBuilder.MODE_UPLOADER
                            keyword = user
                        },
                    ),
                ),
            )
        },
        onOpenLink = { url -> UrlOpener.openUrl(context, url, true, vm.detail) },
        onComposerSend = { text, commentId ->
            vm.sendComment(text.toSpannable().toBBCode().trim(), commentId)
        },
        onComposerDismiss = vm::dismissComposer,
    )
}
