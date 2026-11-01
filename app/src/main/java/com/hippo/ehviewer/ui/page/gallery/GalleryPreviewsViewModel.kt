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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryPreview
import com.hippo.ehviewer.client.data.PreviewSet
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG
import com.hippo.util.ExceptionUtils

/**
 * 预览图页的「页块」状态。
 *
 * 出壳前这套状态住在 `SceneFragment` 里，`onDestroyView` 时还要手动清一遍 `inFlight`
 * （view 重建会取消请求，不清就会「既不加载也不显示进度」）。搬到 ViewModel 之后
 * 请求跟着 [viewModelScope] 走，「重建时清标记」这个补丁就不需要了。
 *
 * 页块（[PreviewBlock]）记录每个服务端分页在合并列表里的起点，滚动定位与「当前第几页」
 * 都从它反推——语义与出壳前的 `ContentHelper` 版本一致：
 * - `scrollTo = -1`：详情页已经展示完首页预览，从第二页接着看
 * - `scrollTo > 0`：从首页开始，拿到数据后定位到该下标
 */
class GalleryPreviewsViewModel(private val appContext: Context) : ViewModel() {

    var items by mutableStateOf(emptyList<GalleryPreview>())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var topPage by mutableIntStateOf(0)
        private set
    var scrollToIndex by mutableStateOf<Int?>(null)
        private set

    /** 一次性提示（越界、后续页加载失败） */
    var tip by mutableStateOf<String?>(null)
        private set
    var tipLength by mutableIntStateOf(TIP_LENGTH_LONG)
        private set

    /** 打开阅读器要整块 `GalleryInfo`；也是 `previewPages` 的来源 */
    var gallery by mutableStateOf<GalleryDetail?>(null)
        private set

    val totalPages: Int
        get() = gallery?.previewPages?.coerceAtLeast(1) ?: 1

    val loading: Boolean
        get() = items.isEmpty() && inFlight.isNotEmpty()

    val loadingMore: Boolean
        get() = items.isNotEmpty() && inFlight.isNotEmpty()

    private var inFlight by mutableStateOf(emptySet<Int>())
    private var blocks = emptyList<PreviewBlock>()
    private var startPage = 0
    private var pendingScrollPage: Int? = null
    private var pendingScrollIndex: Int? = null
    private var started = false

    /**
     * 入口只生效一次：旋转重组不会重新取数。
     *
     * [detail] 由场景从 `galleryDetailCache` 里取好后传进来；拿不到就与出壳前
     * 「arguments 里没有 GalleryInfo」同样处理——直接报找不到画廊。
     */
    fun start(scrollTo: Int, detail: GalleryDetail?) {
        if (started) return
        started = true
        if (detail == null) {
            error = appContext.getString(R.string.error_cannot_find_gallery)
            return
        }
        gallery = detail
        startPage = if (scrollTo == -1) 1 else 0
        if (scrollTo > 0) pendingScrollIndex = scrollTo
        loadPage(startPage)
    }

    fun retry() {
        error = null
        loadPage(startPage)
    }

    fun loadNextPage() {
        val last = blocks.lastOrNull()?.page ?: return
        loadPage(last + 1)
    }

    /** 跳转到指定页（0 基）；该页尚未加载时先取数，到货后再滚动 */
    fun goToPage(page: Int) {
        if (page < 0 || page >= totalPages) {
            showTip(appContext.getString(R.string.error_out_of_range))
            return
        }
        val block = blocks.firstOrNull { it.page == page }
        if (block != null) {
            scrollToIndex = block.start
        } else {
            pendingScrollPage = page
            loadPage(page)
        }
    }

    fun onTopIndexChange(index: Int) {
        topPage = pageForIndex(index)
    }

    fun onScrollHandled() {
        scrollToIndex = null
    }

    fun consumeTip(): String? = tip.also { tip = null }

    private fun loadPage(requestPage: Int) {
        val info: GalleryInfo = gallery ?: return
        if (requestPage < 0 || requestPage >= totalPages) return
        if (blocks.any { it.page == requestPage } || requestPage in inFlight) return
        inFlight = inFlight + requestPage
        if (items.isEmpty()) error = null
        EhRequest()
            .setMethod(EhClient.METHOD_GET_PREVIEW_SET)
            .setArgs(EhUrl.getGalleryDetailUrl(info.gid, info.token, requestPage, false))
            .setCallback(
                object : EhClient.Callback<Pair<PreviewSet, Int>> {
                    override fun onSuccess(result: Pair<PreviewSet, Int>) {
                        onPreviewSet(result.first, requestPage)
                    }

                    override fun onFailure(e: Exception) {
                        onPreviewSetFailure(requestPage, e)
                    }

                    override fun onCancel() {
                        inFlight = inFlight - requestPage
                    }
                },
            )
            .enqueue(viewModelScope)
    }

    private fun onPreviewSet(previewSet: PreviewSet, page: Int) {
        inFlight = inFlight - page
        val info = gallery ?: return
        val size = previewSet.size()
        val list = ArrayList<GalleryPreview>(size)
        for (i in 0 until size) {
            list.add(previewSet.getGalleryPreview(info.gid, i))
        }
        val start = items.size
        blocks = blocks + PreviewBlock(page, start, list)
        items = items + list

        if (pendingScrollPage == page) {
            pendingScrollPage = null
            scrollToIndex = start
        }
        pendingScrollIndex?.let { index ->
            if (index < items.size) {
                pendingScrollIndex = null
                scrollToIndex = index
            }
        }
    }

    private fun onPreviewSetFailure(page: Int, e: Exception) {
        inFlight = inFlight - page
        val message = ExceptionUtils.getReadableString(e)
        if (items.isEmpty()) {
            error = message
        } else {
            showTip(message)
        }
    }

    private fun pageForIndex(index: Int): Int {
        var result = blocks.firstOrNull()?.page ?: 0
        for (block in blocks) {
            if (index >= block.start) result = block.page else break
        }
        return result
    }

    private fun showTip(message: String, length: Int = TIP_LENGTH_LONG) {
        tip = message
        tipLength = length
    }

    private data class PreviewBlock(
        val page: Int,
        val start: Int,
        val items: List<GalleryPreview>,
    )
}
