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

import java.io.File

/**
 * 一页的加载状态，**没有 Failed**。
 *
 * 失败不放在这里，而是把整条目换成 [ReaderItemUi.Error]——照抄 legado 漫画阅读器的做法：
 * 错误是分页器里的一个一等公民条目（可滑过、可点重试），不是一个打断翻页的全局错误态。
 */
sealed interface ReaderPageLoadState {
    /** 还没轮到它（预取窗口之外） */
    data object Queued : ReaderPageLoadState

    /** 正在下载；[progress] 为 null 表示不确定进度（服务端没给 `Content-Length`） */
    data class Loading(val progress: Int? = null) : ReaderPageLoadState

    /** 已落成本地文件，可以交给 Coil / telephoto */
    data class Ready(val file: File) : ReaderPageLoadState
}

/**
 * 分页器的条目。
 *
 * EhViewer 不做跨画廊连续阅读，所以 legado 的 `ChapterEdge` / `ChapterTransition` 整层省掉，
 * 只剩 [Page] 与 [Error]，并且下标与页码 1:1。
 */
sealed interface ReaderItemUi {
    val index: Int

    /** 分页器的稳定 key：加载态变化不该重建条目，失败/成功切换才该重建 */
    val key: String

    data class Page(override val index: Int, val loadState: ReaderPageLoadState) : ReaderItemUi {
        override val key: String
            get() = "page-$index"
    }

    data class Error(override val index: Int, val message: String) : ReaderItemUi {
        override val key: String
            get() = "error-$index"
    }
}

/** 旧 `GalleryActivity` 的 `mMaskView` 在最暗时的 alpha（0xde） */
private const val DIM_MAX_ALPHA = 0xde / 255f

/**
 * 阅读设置。
 *
 * 只放布尔与标量，不放 `GalleryView.LAYOUT_*` / `SCALE_*` 这些旧常量——
 * 常量到字段的互转集中在 `ReaderSettingsStore`，这样这一层不碰 Android，可以直接单测。
 */
data class ReaderSettings(
    /** 竖向逐页阅读（`LAYOUT_TOP_TO_BOTTOM`） */
    val vertical: Boolean = false,
    /** 横向时从右往左翻（`LAYOUT_RIGHT_TO_LEFT`） */
    val reverseLayout: Boolean = false,
    /** 缩放模式，取值与 `GalleryView.SCALE_*` 同序；到 `ContentScale` 的映射在 UI 层 */
    val scaleMode: Int = SCALE_FIT,
    /** 图片小于视口时的锚点，与 `ImageView.START_POSITION_*` 同序 */
    val startPosition: Int = START_POSITION_TOP_RIGHT,
    val showPageInterval: Boolean = false,
    /** 屏幕方向，与 `Settings.screenRotation` 同序：0 默认 / 1 竖 / 2 横 / 3 自动 */
    val screenRotation: Int = 0,
    val fullscreen: Boolean = false,
    /** 阅读时保持屏幕常亮（`FLAG_KEEP_SCREEN_ON`） */
    val keepScreenOn: Boolean = false,
    /** 自动翻页间隔（秒）；0 = 关。开关本身不持久化，与旧实现一致 */
    val autoPageInterval: Int = 0,
    val volumePage: Boolean = false,
    /** 一次按音量键翻几页 */
    val volumePageInterval: Int = 1,
    val reverseVolumePage: Boolean = false,
    /**
     * 阅读主题：0 跟随 App / 1 深色 / 2 浅色。
     *
     * 旧实现靠 `GalleryActivity.attachBaseContext` 改 `localNightMode`，改完必须
     * `recreate()`；换成 NavHost 单宿主后代价太大，改成在阅读器子树里换一套色板。
     */
    val readTheme: Int = READ_THEME_FOLLOW_APP,
    val customScreenLightness: Boolean = false,
    /** 0 - 200：<= 100 靠遮罩压暗，> 100 才调 Window 亮度 */
    val screenLightness: Int = 100,
) {
    /** 压暗遮罩的 alpha；关掉自定义亮度时为 0 */
    val dimAlpha: Float
        get() = if (customScreenLightness && screenLightness <= 100) {
            (DIM_MAX_ALPHA * (1f - screenLightness / 100f)).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 命令式跳页。
 *
 * [id] 必须自增：不带 id 的话重组一次就会重复触发一次跳页，快速连点时能把分页器弹回去。
 */
data class ReaderScrollRequest(val id: Long, val page: Int)

/** 阅读器 UI 状态；所有变更都经 [reduce]，纯函数、可断言 */
data class ReaderUiState(
    val pages: List<ReaderItemUi> = emptyList(),
    val currentPage: Int = 0,
    /** 最近一次程序滚动的 id；0 表示从来没程序滚动过 */
    val navigationId: Long = 0L,
    val scrollRequest: ReaderScrollRequest? = null,
    /** 页源是不是在线来源——只有在线才有「下载原图」这一项 */
    val online: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
) {
    val count: Int
        get() = pages.size
}

sealed interface ReaderIntent {
    /** 预取：把窗口内还没起动的页标成 Loading（真正去取由 ViewModel 补上） */
    data class Prefetch(val indices: List<Int>) : ReaderIntent

    /** 点错误占位上的「重试」 */
    data class Retry(val index: Int) : ReaderIntent

    /** 刷新：丢掉这一页的缓存重新下（页面菜单的「刷新」） */
    data class Refresh(val index: Int) : ReaderIntent

    /** 跳到 [page]（0 基）；越界会被钳到边界 */
    data class ScrollTo(val page: Int) : ReaderIntent

    /**
     * 相对翻 [delta] 页（正数往后）。
     *
     * 点屏幕左右 1/3、按音量键都是一次离散步进，用相对量描述，UI 层就不必读
     * `currentPage`——手势的 `pointerInput` key 因此能保持稳定，不会因为翻一页就把
     * 正在等待的双击超时打断。
     */
    data class PageStep(val delta: Int) : ReaderIntent

    /**
     * 分页器停在 [page]。
     *
     * [navigationId] 为 null 表示用户滑的；非 null 表示刚执行完 [ReaderScrollRequest]，
     * 值与 [ReaderUiState.navigationId] 对不上就是过期的滚动回弹，直接丢弃。
     */
    data class PageSettled(val page: Int, val navigationId: Long? = null) : ReaderIntent

    /**
     * 改设置：整块替换，而不是 N 个 `setBrightness` / `setScaleType`。
     *
     * 持久化（写回 `Settings`）由 ViewModel 在归约之后做。
     */
    data class UpdateSettings(val settings: ReaderSettings) : ReaderIntent
}

/**
 * 纯归约：只算新状态，副作用（真的去取某一页、回写进度）由 ViewModel 在归约之后补。
 *
 * 拆开是为了让「页码钳制 / navigationId 过期过滤 / 加载态迁移」这三件事能直接断言。
 */
fun ReaderUiState.reduce(intent: ReaderIntent): ReaderUiState = when (intent) {
    is ReaderIntent.Prefetch -> {
        // 只有 Queued 才推进；Ready 与 Error 都要留着，前者不能重新下载，后者要等显式重试
        val next = ArrayList(pages)
        var changed = false
        for (index in intent.indices) {
            val item = next.getOrNull(index)
            if (item is ReaderItemUi.Page && item.loadState == ReaderPageLoadState.Queued) {
                next[index] = ReaderItemUi.Page(index, ReaderPageLoadState.Loading())
                changed = true
            }
        }
        // 窗口里全是下好的页时保持同一个实例，免得每翻一页都白重组一次
        if (changed) copy(pages = next) else this
    }
    is ReaderIntent.Retry -> withPage(intent.index) { item ->
        if (item is ReaderItemUi.Error) ReaderItemUi.Page(intent.index, ReaderPageLoadState.Loading()) else item
    }
    // 刷新与重试的状态迁移是一样的，区别只在 ViewModel 那头要不要先 invalidate
    is ReaderIntent.Refresh -> withPage(intent.index) {
        ReaderItemUi.Page(intent.index, ReaderPageLoadState.Loading())
    }
    is ReaderIntent.ScrollTo -> {
        val target = intent.page.coerceIn(0, (count - 1).coerceAtLeast(0))
        if (target == currentPage) {
            copy(scrollRequest = null)
        } else {
            val id = navigationId + 1
            copy(currentPage = target, navigationId = id, scrollRequest = ReaderScrollRequest(id, target))
        }
    }
    // 相对步进就是「以当前页为基准的绝对跳转」，钳位与自增 id 都复用同一条路径
    is ReaderIntent.PageStep -> reduce(ReaderIntent.ScrollTo(currentPage + intent.delta))
    is ReaderIntent.PageSettled -> {
        val echo = intent.navigationId
        if (echo != null && echo != navigationId) {
            this // 过期回弹：丢掉，免得把刚跳过去的页码又写回来
        } else {
            copy(currentPage = intent.page, scrollRequest = null)
        }
    }
    is ReaderIntent.UpdateSettings -> copy(settings = intent.settings)
}

/** 换掉第 [index] 个条目；越界保持原样 */
fun ReaderUiState.withPage(index: Int, block: (ReaderItemUi) -> ReaderItemUi): ReaderUiState {
    if (index !in pages.indices) return this
    val next = pages.toMutableList()
    next[index] = block(next[index])
    return copy(pages = next)
}

/** 更新下载进度；只有正在 Loading 的条目才吃，免得把 Ready 的文件冲掉 */
fun ReaderUiState.withProgress(index: Int, percent: Int?): ReaderUiState = withPage(index) { item ->
    if (item is ReaderItemUi.Page && item.loadState is ReaderPageLoadState.Loading) {
        ReaderItemUi.Page(index, ReaderPageLoadState.Loading(percent))
    } else {
        item
    }
}
