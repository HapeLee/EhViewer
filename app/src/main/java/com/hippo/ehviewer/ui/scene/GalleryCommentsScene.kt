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
package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryComment
import com.hippo.ehviewer.client.data.GalleryCommentList
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.parser.VoteCommentParser
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.screen.GalleryCommentsScreen
import com.hippo.ehviewer.ui.compose.state.GalleryCommentsStore
import com.hippo.ehviewer.ui.compose.text.toSpannable
import com.hippo.util.ExceptionUtils
import com.hippo.util.addTextToClipboard
import com.hippo.util.getParcelableCompat
import com.hippo.util.toBBCode

/**
 * 画廊评论页。
 *
 * 迁移点：
 * - `CommentAdapter` / `ViewTransition` / `SwipeRefreshLayout` 由 Compose 列表接管
 * - 评论数据改成不可变的 `List<GalleryComment>` 状态；对象本身仍是旧的 Parcelable 可变类，
 *   所以这里用 [neverEqualPolicy] 声明「赋值即刷新」，避免原地改 score 后不重组
 * - 评论变更统一写 `GalleryCommentsStore`，详情页回前台时自己读，不再走 `setResult` 回传
 */
class GalleryCommentsScene : EhComposeScene() {

    private var galleryDetail: GalleryDetail? = null
    private var comments by mutableStateOf(emptyList<GalleryComment>(), neverEqualPolicy())
    private var hasMore by mutableStateOf(false)
    private var showAllComments = false
    private var refreshing by mutableStateOf(false)
    private var loadingMore by mutableStateOf(false)
    private var posting by mutableStateOf(false)
    private var composerVisible by mutableStateOf(false)
    private var editingId by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        galleryDetail = if (savedInstanceState == null) {
            arguments?.getParcelableCompat(KEY_GALLERY_DETAIL)
        } else {
            savedInstanceState.getParcelableCompat(KEY_GALLERY_DETAIL)
        }
        // 仓库优先：重进本页时拿到的是「上次离开前那份」，而不是入参里的旧快照
        val stored = galleryDetail?.gid?.takeIf { it != -1L }?.let { GalleryCommentsStore.latest(it) }
        val list = stored ?: galleryDetail?.comments
        comments = list?.comments?.toList().orEmpty()
        hasMore = list?.hasMore == true
        showAllComments = list != null && !list.hasMore
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_GALLERY_DETAIL, galleryDetail)
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.GalleryComments> {
            GalleryCommentsScreen(
                title = getString(R.string.gallery_comments),
                comments = comments,
                hasMore = hasMore,
                loadingMore = loadingMore,
                refreshing = refreshing,
                composerVisible = composerVisible,
                editingId = editingId,
                posting = posting,
                onBack = { onBackPressed() },
                onRefresh = ::refresh,
                onLoadMore = ::loadMore,
                onNewComment = {
                    editingId = 0
                    composerVisible = true
                },
                onEditComment = { comment ->
                    editingId = comment.id
                    composerVisible = true
                },
                onVote = ::vote,
                onBlockCommenter = ::blockCommenter,
                onCopyComment = { text -> mainActivity?.addTextToClipboard(text, false) },
                onOpenUploader = ::openUploader,
                onOpenLink = { url ->
                    context?.let { UrlOpener.openUrl(it, url, true, galleryDetail) }
                },
                onComposerSend = ::sendComment,
                onComposerDismiss = {
                    composerVisible = false
                    hideSoftInput()
                },
            )
        }
    }

    override fun onBackPressed() {
        if (composerVisible) {
            composerVisible = false
            hideSoftInput()
        } else {
            finish()
        }
    }

    private fun detailUrl(): String? {
        val detail = galleryDetail ?: return null
        if (detail.gid == -1L || detail.token == null) return null
        return EhUrl.getGalleryDetailUrl(detail.gid, detail.token, 0, showAllComments)
    }

    private fun refresh() {
        if (refreshing || loadingMore) return
        val url = detailUrl() ?: return
        val ctx = context ?: return
        refreshing = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(url)
            .setCallback(CommentsListener(ctx))
            .enqueue(this)
    }

    private fun loadMore() {
        if (refreshing || loadingMore) return
        showAllComments = true
        val url = detailUrl() ?: return
        val ctx = context ?: return
        loadingMore = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(url)
            .setCallback(CommentsListener(ctx))
            .enqueue(this)
    }

    private fun sendComment(text: AnnotatedString, commentId: Long) {
        if (posting) return
        val url = detailUrl() ?: return
        val ctx = context ?: return
        val bbcode = text.toSpannable().toBBCode().trim()
        if (bbcode.isEmpty()) {
            showTip(R.string.text_is_empty, LENGTH_SHORT)
            return
        }
        posting = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_COMMENT_GALLERY)
            .setArgs(url, bbcode, if (commentId != 0L) commentId.toString() else null)
            .setCallback(CommentListener(ctx, commentId))
            .enqueue(this)
    }

    private fun vote(comment: GalleryComment, vote: Int) {
        val detail = galleryDetail ?: return
        val ctx = context ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_VOTE_COMMENT)
            .setArgs(detail.apiUid, detail.apiKey, detail.gid, detail.token, comment.id, vote)
            .setCallback(VoteListener(ctx))
            .enqueue(this)
    }

    private fun blockCommenter(comment: GalleryComment) {
        val name = comment.user ?: return
        val filter = Filter().apply {
            mode = EhFilter.MODE_COMMENTER
            text = name
        }
        EhFilter.addFilter(filter)
        comments = comments.filterNot { it.user == name }
        showTip(R.string.filter_added, LENGTH_SHORT)
        publishComments()
    }

    private fun openUploader(user: String) {
        val lub = ListUrlBuilder().apply {
            mode = ListUrlBuilder.MODE_UPLOADER
            keyword = user
        }
        GalleryListScene.open(this, lub)
    }

    private fun onCommentsSuccess(list: GalleryCommentList) {
        refreshing = false
        loadingMore = false
        comments = list.comments?.toList().orEmpty()
        hasMore = list.hasMore
        publishComments()
    }

    private fun onCommentsFailure(e: Exception) {
        refreshing = false
        loadingMore = false
        showTip(ExceptionUtils.getReadableString(e), LENGTH_LONG)
    }

    private fun onCommentSuccess(list: GalleryCommentList, commentId: Long) {
        posting = false
        composerVisible = false
        editingId = 0
        hideSoftInput()
        comments = list.comments?.toList().orEmpty()
        hasMore = list.hasMore
        showTip(
            if (commentId != 0L) R.string.edit_comment_successfully else R.string.comment_successfully,
            LENGTH_SHORT,
        )
        publishComments()
    }

    private fun onCommentFailure(e: Exception, commentId: Long) {
        posting = false
        val title = getString(
            if (commentId != 0L) R.string.edit_comment_failed else R.string.comment_failed,
        )
        showTip("$title\n${ExceptionUtils.getReadableString(e)}", LENGTH_LONG)
    }

    private fun onVoteSuccess(result: VoteCommentParser.Result) {
        val target = comments.firstOrNull { it.id == result.id }
        if (target == null) {
            showTip(R.string.vote_failed, LENGTH_LONG)
            return
        }
        target.score = result.score
        if (result.expectVote > 0) {
            target.voteUpEd = result.vote != 0
            target.voteDownEd = false
        } else {
            target.voteDownEd = result.vote != 0
            target.voteUpEd = false
        }
        // 对象是就地修改的，重新赋值一次触发重组
        comments = comments.toList()
        showTip(
            when {
                result.expectVote > 0 && result.vote != 0 -> R.string.vote_up_successfully
                result.expectVote > 0 -> R.string.cancel_vote_up_successfully
                result.vote != 0 -> R.string.vote_down_successfully
                else -> R.string.cancel_vote_down_successfully
            },
            LENGTH_SHORT,
        )
        publishComments()
    }

    private fun onVoteFailure() {
        showTip(R.string.vote_failed, LENGTH_LONG)
    }

    /**
     * 把最新评论写进 [GalleryCommentsStore]。
     *
     * 取代旧的 `setResult(KEY_COMMENT_LIST)`：详情页不在这里找，它回到前台时自己读仓库，
     * 所以详情页被回收重建也不会丢更新。
     */
    private fun publishComments() {
        val currentGid = galleryDetail?.gid ?: return
        GalleryCommentsStore.publish(
            currentGid,
            GalleryCommentList(comments.toTypedArray(), hasMore),
        )
    }

    private inner class CommentsListener(context: Context) :
        EhCallback<GalleryCommentsScene, GalleryDetail>(context) {
        override fun onSuccess(result: GalleryDetail) {
            val list = result.comments
            if (list == null) {
                this@GalleryCommentsScene.onCommentsFailure(
                    IllegalStateException("Gallery has no comments"),
                )
            } else {
                this@GalleryCommentsScene.onCommentsSuccess(list)
            }
        }

        override fun onFailure(e: Exception) {
            this@GalleryCommentsScene.onCommentsFailure(e)
        }

        override fun onCancel() {
            refreshing = false
            loadingMore = false
        }
    }

    private inner class CommentListener(context: Context, private val commentId: Long) :
        EhCallback<GalleryCommentsScene, GalleryCommentList>(context) {
        override fun onSuccess(result: GalleryCommentList) {
            this@GalleryCommentsScene.onCommentSuccess(result, commentId)
        }

        override fun onFailure(e: Exception) {
            this@GalleryCommentsScene.onCommentFailure(e, commentId)
        }

        override fun onCancel() {
            posting = false
        }
    }

    private inner class VoteListener(context: Context) :
        EhCallback<GalleryCommentsScene, VoteCommentParser.Result>(context) {
        override fun onSuccess(result: VoteCommentParser.Result) {
            this@GalleryCommentsScene.onVoteSuccess(result)
        }

        override fun onFailure(e: Exception) {
            this@GalleryCommentsScene.onVoteFailure()
        }

        override fun onCancel() {}
    }

    companion object {
        val TAG: String = GalleryCommentsScene::class.java.simpleName
        const val KEY_API_UID = "api_uid"
        const val KEY_API_KEY = "api_key"
        const val KEY_GID = "gid"
        const val KEY_TOKEN = "token"
        const val KEY_COMMENT_LIST = "comment_list"
        const val KEY_GALLERY_DETAIL = "gallery_detail"
    }
}
