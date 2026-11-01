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
package com.hippo.ehviewer.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhDB
import com.hippo.util.ExceptionUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 阅读器的状态机：`onIntent` 单入口，状态变更走 [reduce] 这个纯函数，
 * 副作用（真的去取某一页、回写阅读进度）在归约之后补。
 *
 * 初始状态在构造函数里就位：分页器第一帧就必须知道有多少页，
 * 否则 `rememberPagerState` 会从 0 页开始再跳一次。
 */
class ReaderViewModel(
    private val pageSource: PageSource,
    initialPage: Int,
    settings: ReaderSettings,
    private val gid: Long = -1L,
) : ViewModel() {

    var uiState by mutableStateOf(
        ReaderUiState(
            pages = List(pageSource.count) { ReaderItemUi.Page(it, ReaderPageLoadState.Queued) },
            currentPage = initialPage.coerceIn(0, (pageSource.count - 1).coerceAtLeast(0)),
            online = pageSource is OnlinePageSource,
            settings = settings,
        ),
    )
        private set

    private val jobs = HashMap<Int, Job>()

    init {
        viewModelScope.launch {
            pageSource.progress.collect { uiState = uiState.withProgress(it.index, it.percent) }
        }
    }

    fun onIntent(intent: ReaderIntent) {
        uiState = uiState.reduce(intent)
        when (intent) {
            is ReaderIntent.Prefetch -> intent.indices.forEach(::load)
            is ReaderIntent.Retry -> load(intent.index)
            is ReaderIntent.Refresh -> {
                jobs[intent.index]?.cancel()
                pageSource.invalidate(intent.index)
                load(intent.index)
            }
            // 归约可能已经把这次回弹丢掉了，此时 currentPage 没变，写回去也是同一个值
            is ReaderIntent.PageSettled -> rememberProgress(uiState.currentPage)
            is ReaderIntent.UpdateSettings -> uiState.settings.persist()
            is ReaderIntent.ScrollTo -> Unit
            // 归约里已经转成 ScrollTo 了，滚动的执行在 UI 层
            is ReaderIntent.PageStep -> Unit
        }
    }

    /**
     * 记进度：两份都写。
     *
     * `PageSource.putStartPage` 落在 `.ehviewer`（跟着缓存目录，清缓存就没），
     * Room 那份才是跨设备/清缓存都还在的，R4 起以它为准。
     */
    private fun rememberProgress(page: Int) {
        if (gid == -1L) return
        pageSource.putStartPage(page)
        viewModelScope.launch(Dispatchers.IO) { EhDB.putReadingProgress(gid, page) }
    }

    /** 页面菜单要的当前页文件：已经下好就直接给，否则现场下一遍 */
    suspend fun file(index: Int): File? {
        val item = uiState.pages.getOrNull(index)
        if (item is ReaderItemUi.Page && item.loadState is ReaderPageLoadState.Ready) {
            return item.loadState.file
        }
        return runCatching { pageSource.page(index) }.getOrNull()
    }

    /** 页面菜单的「下载原图」：只有在线画廊拿得到，其余返回 null */
    suspend fun original(index: Int, dir: File): File? = pageSource.original(index, dir)

    private fun load(index: Int) {
        if (index !in uiState.pages.indices) return
        if (jobs[index]?.isActive == true) return
        val item = uiState.pages[index]
        if (item is ReaderItemUi.Page && item.loadState is ReaderPageLoadState.Ready) return
        jobs[index] = viewModelScope.launch {
            runCatching { pageSource.page(index) }
                .onSuccess { file ->
                    uiState = uiState.withPage(index) { ReaderItemUi.Page(index, ReaderPageLoadState.Ready(file)) }
                }
                .onFailure { e ->
                    uiState = uiState.withPage(index) { ReaderItemUi.Error(index, ExceptionUtils.getReadableString(e)) }
                }
        }
    }
}
