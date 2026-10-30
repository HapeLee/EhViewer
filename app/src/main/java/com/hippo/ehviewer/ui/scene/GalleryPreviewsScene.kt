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
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryPreview
import com.hippo.ehviewer.client.data.PreviewSet
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.screen.GalleryPreviewsScreen
import com.hippo.util.ExceptionUtils
import com.hippo.util.getParcelableCompat

/**
 * 画廊预览图页。
 *
 * 迁移点：
 * - `ContentLayout` / `ContentHelper` 的分页状态改为这里显式持有的「页块」列表，
 *   `GalleryPreviewAdapter`、`NotifyDataSetChanged` 和 RecyclerView 生命周期一并消失
 * - `GalleryDetailScene` 传进来的 [KEY_SCROLL_TO] 语义保持不变：
 *   `-1` 表示详情页已经展示完第一页预览，从第二页接着看；正数表示在首页内定位到该下标
 * - 返回本页（view 重建）不会重新请求；只有一页都没拿到时才会重新加载
 */
class GalleryPreviewsScene : EhComposeScene() {

    private var galleryInfo: GalleryInfo? = null
    private var startPage = 0

    /** 已加载的页块，按请求完成的先后追加；页码只影响取数，不影响展示顺序 */
    private var blocks by mutableStateOf<List<PreviewBlock>>(emptyList())
    private var items by mutableStateOf<List<GalleryPreview>>(emptyList())
    private var inFlight by mutableStateOf<Set<Int>>(emptySet())
    private var error by mutableStateOf<String?>(null)
    private var topPage by mutableStateOf(0)
    private var scrollToIndex by mutableStateOf<Int?>(null)
    private var pendingScrollPage by mutableStateOf<Int?>(null)
    private var pendingScrollIndex by mutableStateOf<Int?>(null)

    private val totalPages: Int
        get() = (galleryInfo as? GalleryDetail)?.previewPages?.coerceAtLeast(1) ?: 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = if (savedInstanceState == null) arguments?.getInt(KEY_SCROLL_TO) ?: 0 else 0
        galleryInfo = if (savedInstanceState == null) {
            arguments?.getParcelableCompat(KEY_GALLERY_INFO)
        } else {
            savedInstanceState.getParcelableCompat(KEY_GALLERY_INFO)
        }
        // 详情页已经把首页预览都展示过时（-1），直接从第二页开始；否则从首页开始、按需定位
        startPage = if (scroll == -1) 1 else 0
        if (scroll > 0) pendingScrollIndex = scroll
    }

    override fun onDestroyView() {
        // view 重建会连同 viewLifecycleOwner 的请求一起取消，这里同步清掉在途标记，
        // 否则下次进入会因为「还在请求中」而既不加载也不显示进度
        inFlight = emptySet()
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_GALLERY_INFO, galleryInfo)
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.GalleryPreviews> {
            LaunchedEffect(Unit) { initialLoad() }
            val itemSizePx = remember { Settings.previewSize }
            GalleryPreviewsScreen(
                title = getString(R.string.gallery_previews),
                items = items,
                itemSizePx = itemSizePx,
                loading = items.isEmpty() && inFlight.isNotEmpty(),
                loadingMore = items.isNotEmpty() && inFlight.isNotEmpty(),
                error = error,
                totalPages = totalPages,
                topPage = topPage,
                onBack = { finish() },
                onItemClick = ::openPage,
                onLoadMore = ::loadNextPage,
                onRetry = {
                    error = null
                    initialLoad(force = true)
                },
                onTopIndexChange = { index -> topPage = pageForIndex(index) },
                scrollToIndex = scrollToIndex,
                onScrollHandled = { scrollToIndex = null },
                onGoToPage = ::goToPage,
            )
        }
    }

    private fun initialLoad(force: Boolean = false) {
        if (galleryInfo == null) {
            error = getString(R.string.error_cannot_find_gallery)
            return
        }
        if (!force && (items.isNotEmpty() || inFlight.isNotEmpty())) return
        loadPage(startPage)
    }

    private fun loadNextPage() {
        val last = blocks.lastOrNull()?.page ?: return
        loadPage(last + 1)
    }

    private fun loadPage(requestPage: Int) {
        val info = galleryInfo ?: return
        if (requestPage < 0 || requestPage >= totalPages) return
        if (blocks.any { it.page == requestPage } || requestPage in inFlight) return
        val ctx = context ?: return
        inFlight = inFlight + requestPage
        if (items.isEmpty()) error = null
        val request = EhRequest()
            .setMethod(EhClient.METHOD_GET_PREVIEW_SET)
            .setArgs(EhUrl.getGalleryDetailUrl(info.gid, info.token, requestPage, false))
            .setCallback(GetPreviewSetListener(ctx, requestPage))
        request.enqueue(this)
    }

    /** 跳转到指定页（0 基）；该页尚未加载时先取数，到货后再滚动 */
    private fun goToPage(page: Int) {
        if (page < 0 || page >= totalPages) {
            showTip(R.string.error_out_of_range, LENGTH_LONG)
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

    private fun openPage(preview: GalleryPreview) {
        val ctx = context ?: return
        val info = galleryInfo ?: return
        val intent = Intent(ctx, GalleryActivity::class.java).apply {
            action = GalleryActivity.ACTION_EH
            putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
            putExtra(GalleryActivity.KEY_PAGE, preview.position)
        }
        startActivity(intent)
    }

    private fun onGetPreviewSetSuccess(page: Int, previewSet: PreviewSet) {
        inFlight = inFlight - page
        val info = galleryInfo ?: return
        val size = previewSet.size()
        val list = ArrayList<GalleryPreview>(size)
        for (i in 0 until size) {
            list.add(previewSet.getGalleryPreview(info.gid, i))
        }
        val start = items.size
        blocks = blocks + PreviewBlock(page, start, list)
        items = items + list

        pendingScrollPage?.let { target ->
            if (target == page) {
                pendingScrollPage = null
                scrollToIndex = start
            }
        }
        pendingScrollIndex?.let { index ->
            if (index < items.size) {
                pendingScrollIndex = null
                scrollToIndex = index
            }
        }
    }

    private fun onGetPreviewSetFailure(page: Int, e: Exception) {
        inFlight = inFlight - page
        val message = ExceptionUtils.getReadableString(e)
        if (items.isEmpty()) {
            error = message
        } else {
            showTip(message, LENGTH_LONG)
        }
    }

    private fun pageForIndex(index: Int): Int {
        var result = blocks.firstOrNull()?.page ?: 0
        for (block in blocks) {
            if (index >= block.start) result = block.page else break
        }
        return result
    }

    private inner class GetPreviewSetListener(
        context: Context,
        private val page: Int,
    ) : EhCallback<GalleryPreviewsScene, Pair<PreviewSet, Int>>(context) {
        override fun onSuccess(result: Pair<PreviewSet, Int>) {
            this@GalleryPreviewsScene.onGetPreviewSetSuccess(page, result.first)
        }

        override fun onFailure(e: Exception) {
            this@GalleryPreviewsScene.onGetPreviewSetFailure(page, e)
        }

        override fun onCancel() {
            inFlight = inFlight - page
        }
    }

    private data class PreviewBlock(
        val page: Int,
        val start: Int,
        val items: List<GalleryPreview>,
    )

    companion object {
        const val KEY_GALLERY_INFO = "gallery_info"
        const val KEY_SCROLL_TO = "scroll_to"
    }
}
