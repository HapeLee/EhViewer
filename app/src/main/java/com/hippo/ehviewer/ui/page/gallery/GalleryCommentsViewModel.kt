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
package com.hippo.ehviewer.ui.page.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryComment
import com.hippo.ehviewer.client.data.GalleryCommentList
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.parser.VoteCommentParser
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.state.GalleryCommentsStore
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG
import com.hippo.ehviewer.ui.host.TIP_LENGTH_SHORT
import com.hippo.util.ExceptionUtils

/**
 * 评论页的状态与网络逻辑。
 *
 * 出壳前这些住在 `SceneFragment` 里，`onSaveInstanceState` 存整块 `GalleryDetail`。
 * 现在只用 route 里的 `gid` / `token` / `apiUid` / `apiKey` 发请求，
 * `GalleryDetail` 只在「首次评论列表从哪来」和「打开链接时带画廊上下文」时用到。
 *
 * 两个刻意的老规矩：
 * - 评论对象是旧的 Parcelable 可变类，原地改 `score` 不会改变引用，
 *   所以 [comments] 用 `neverEqualPolicy`，并且投票后重新赋值一次触发重组
 * - 评论变更统一写 [GalleryCommentsStore]，详情页回前台自己读，不再走 `setResult` 回传
 */
class GalleryCommentsViewModel(private val appContext: Context) : ViewModel() {

    var comments by mutableStateOf(emptyList<GalleryComment>(), neverEqualPolicy())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var posting by mutableStateOf(false)
        private set
    var composerVisible by mutableStateOf(false)
        private set
    var editingId by mutableLongStateOf(0L)
        private set

    /** 画廊详情，可能为空（缓存未命中）；「打开链接」时作为上下文传下去 */
    var detail by mutableStateOf<GalleryDetail?>(null)
        private set

    /** 每次关掉评论框 +1，场景据此收键盘（初值 0 不触发） */
    var hideKeyboardRequests by mutableIntStateOf(0)
        private set

    /** 一次性提示 */
    var tip by mutableStateOf<String?>(null)
        private set
    var tipLength by mutableIntStateOf(TIP_LENGTH_LONG)
        private set

    private var route: EhRoute.GalleryComments? = null
    private var showAllComments = false
    private var started = false

    fun start(route: EhRoute.GalleryComments, detail: GalleryDetail?) {
        if (started) return
        started = true
        this.route = route
        this.detail = detail
        // 仓库优先：重进本页时拿到的是「上次离开前那份」，而不是入参里的旧快照
        val stored = route.gid.takeIf { it != -1L }?.let { GalleryCommentsStore.latest(it) }
        val list = stored ?: detail?.comments
        comments = list?.comments?.toList().orEmpty()
        hasMore = list?.hasMore == true
        showAllComments = list != null && !list.hasMore
    }

    fun refresh() {
        if (refreshing || loadingMore) return
        val url = detailUrl() ?: return
        refreshing = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(url)
            .setCallback(
                object : EhClient.Callback<GalleryDetail> {
                    override fun onSuccess(result: GalleryDetail) {
                        val list = result.comments
                        if (list == null) {
                            onCommentsFailure(IllegalStateException("Gallery has no comments"))
                        } else {
                            onCommentsSuccess(list)
                        }
                    }

                    override fun onFailure(e: Exception) = onCommentsFailure(e)

                    override fun onCancel() {
                        refreshing = false
                        loadingMore = false
                    }
                },
            )
            .enqueue(viewModelScope)
    }

    fun loadMore() {
        if (refreshing || loadingMore) return
        showAllComments = true
        val url = detailUrl() ?: return
        loadingMore = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(url)
            .setCallback(
                object : EhClient.Callback<GalleryDetail> {
                    override fun onSuccess(result: GalleryDetail) {
                        val list = result.comments
                        if (list == null) {
                            onCommentsFailure(IllegalStateException("Gallery has no comments"))
                        } else {
                            onCommentsSuccess(list)
                        }
                    }

                    override fun onFailure(e: Exception) = onCommentsFailure(e)

                    override fun onCancel() {
                        refreshing = false
                        loadingMore = false
                    }
                },
            )
            .enqueue(viewModelScope)
    }

    /** [bbcode] 由场景把富文本转好再送进来 */
    fun sendComment(bbcode: String, commentId: Long) {
        if (posting) return
        if (bbcode.isEmpty()) {
            showTip(appContext.getString(R.string.text_is_empty), TIP_LENGTH_SHORT)
            return
        }
        val url = detailUrl() ?: return
        posting = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_COMMENT_GALLERY)
            .setArgs(url, bbcode, if (commentId != 0L) commentId.toString() else null)
            .setCallback(
                object : EhClient.Callback<GalleryCommentList> {
                    override fun onSuccess(result: GalleryCommentList) = onCommentSuccess(result, commentId)

                    override fun onFailure(e: Exception) = onCommentFailure(e, commentId)

                    override fun onCancel() {
                        posting = false
                    }
                },
            )
            .enqueue(viewModelScope)
    }

    fun vote(comment: GalleryComment, vote: Int) {
        val r = route ?: return
        if (r.gid == -1L) return
        EhRequest()
            .setMethod(EhClient.METHOD_VOTE_COMMENT)
            .setArgs(r.apiUid, r.apiKey, r.gid, r.token, comment.id, vote)
            .setCallback(
                object : EhClient.Callback<VoteCommentParser.Result> {
                    override fun onSuccess(result: VoteCommentParser.Result) = onVoteSuccess(result)

                    override fun onFailure(e: Exception) {
                        showTip(appContext.getString(R.string.vote_failed))
                    }

                    override fun onCancel() {}
                },
            )
            .enqueue(viewModelScope)
    }

    fun blockCommenter(comment: GalleryComment) {
        val name = comment.user ?: return
        EhFilter.addFilter(
            Filter().apply {
                mode = EhFilter.MODE_COMMENTER
                text = name
            },
        )
        comments = comments.filterNot { it.user == name }
        showTip(appContext.getString(R.string.filter_added), TIP_LENGTH_SHORT)
        publishComments()
    }

    fun newComment() {
        editingId = 0
        composerVisible = true
    }

    fun editComment(comment: GalleryComment) {
        editingId = comment.id
        composerVisible = true
    }

    fun dismissComposer() {
        if (!composerVisible) return
        composerVisible = false
        hideKeyboardRequests++
    }

    fun consumeTip(): String? = tip.also { tip = null }

    private fun detailUrl(): String? {
        val r = route ?: return null
        if (r.gid == -1L || r.token == null) return null
        return EhUrl.getGalleryDetailUrl(r.gid, r.token, 0, showAllComments)
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
        showTip(ExceptionUtils.getReadableString(e))
    }

    private fun onCommentSuccess(list: GalleryCommentList, commentId: Long) {
        posting = false
        composerVisible = false
        editingId = 0
        hideKeyboardRequests++
        comments = list.comments?.toList().orEmpty()
        hasMore = list.hasMore
        showTip(
            appContext.getString(
                if (commentId != 0L) R.string.edit_comment_successfully else R.string.comment_successfully,
            ),
            TIP_LENGTH_SHORT,
        )
        publishComments()
    }

    private fun onCommentFailure(e: Exception, commentId: Long) {
        posting = false
        val title = appContext.getString(
            if (commentId != 0L) R.string.edit_comment_failed else R.string.comment_failed,
        )
        showTip("$title\n${ExceptionUtils.getReadableString(e)}")
    }

    private fun onVoteSuccess(result: VoteCommentParser.Result) {
        val target = comments.firstOrNull { it.id == result.id }
        if (target == null) {
            showTip(appContext.getString(R.string.vote_failed))
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
            appContext.getString(
                when {
                    result.expectVote > 0 && result.vote != 0 -> R.string.vote_up_successfully
                    result.expectVote > 0 -> R.string.cancel_vote_up_successfully
                    result.vote != 0 -> R.string.vote_down_successfully
                    else -> R.string.cancel_vote_down_successfully
                },
            ),
            TIP_LENGTH_SHORT,
        )
        publishComments()
    }

    /** 把最新评论写进 [GalleryCommentsStore]，详情页回前台自己读 */
    private fun publishComments() {
        val gid = route?.gid ?: return
        GalleryCommentsStore.publish(gid, GalleryCommentList(comments.toTypedArray(), hasMore))
    }

    private fun showTip(message: String, length: Int = TIP_LENGTH_LONG) {
        tip = message
        tipLength = length
    }
}
