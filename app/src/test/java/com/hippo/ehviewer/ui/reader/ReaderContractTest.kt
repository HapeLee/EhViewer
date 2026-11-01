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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器状态机的护栏：页码钳制、`navigationId` 过期回弹过滤、加载态迁移。
 *
 * 这三条都是「重放一次就会出 bug」的逻辑：跳页不带自增 id 会重复触发、
 * 过期回弹会把刚跳过去的页码写回来、预取会把已经下好的页重新下一遍。
 */
class ReaderContractTest {
    private fun state(count: Int, currentPage: Int = 0): ReaderUiState = ReaderUiState(
        pages = List(count) { ReaderItemUi.Page(it, ReaderPageLoadState.Queued) },
        currentPage = currentPage,
    )

    private fun ReaderUiState.loadState(index: Int): ReaderPageLoadState = (pages[index] as ReaderItemUi.Page).loadState

    @Test
    fun `scroll to page is clamped into range`() {
        assertEquals(9, state(10).reduce(ReaderIntent.ScrollTo(99)).currentPage)
        assertEquals(0, state(10).reduce(ReaderIntent.ScrollTo(-5)).currentPage)
    }

    @Test
    fun `scroll to the current page emits no request`() {
        val next = state(10, currentPage = 3).reduce(ReaderIntent.ScrollTo(3))
        assertNull(next.scrollRequest)
        assertEquals(0L, next.navigationId)
    }

    @Test
    fun `scroll request id increases monotonically`() {
        var next = state(10).reduce(ReaderIntent.ScrollTo(5))
        assertEquals(1L, next.scrollRequest!!.id)
        assertEquals(1L, next.navigationId)
        assertEquals(5, next.scrollRequest!!.page)

        next = next.reduce(ReaderIntent.ScrollTo(7))
        assertEquals(2L, next.scrollRequest!!.id)
        assertEquals(2L, next.navigationId)
    }

    @Test
    fun `stale scroll echo is dropped`() {
        val next = state(10)
            .reduce(ReaderIntent.ScrollTo(5))
            .reduce(ReaderIntent.ScrollTo(7))
        // 1 号请求的回弹迟到：它会把页码写回 5，必须丢掉
        val dropped = next.reduce(ReaderIntent.PageSettled(5, 1L))
        assertEquals(7, dropped.currentPage)
        assertNotNull(dropped.scrollRequest)
    }

    @Test
    fun `scroll echo with matching id clears the request`() {
        val next = state(10).reduce(ReaderIntent.ScrollTo(5))
        val settled = next.reduce(ReaderIntent.PageSettled(5, next.navigationId))
        assertNull(settled.scrollRequest)
        assertEquals(5, settled.currentPage)
    }

    @Test
    fun `prefetch only advances queued pages`() {
        var next = state(3)
            .withPage(1) { ReaderItemUi.Page(1, ReaderPageLoadState.Ready(File("/tmp/1"))) }
            .withPage(2) { ReaderItemUi.Error(2, "boom") }
        next = next.reduce(ReaderIntent.Prefetch(listOf(0, 1, 2)))

        assertEquals(ReaderPageLoadState.Loading(), next.loadState(0))
        // 下好的页不能被重新下载，失败的页必须等显式重试
        assertTrue(next.loadState(1) is ReaderPageLoadState.Ready)
        assertTrue(next.pages[2] is ReaderItemUi.Error)
    }

    @Test
    fun `refresh restarts the page but leaves ready ones alone`() {
        val next = state(2)
            .withPage(1) { ReaderItemUi.Page(1, ReaderPageLoadState.Ready(File("/tmp/1"))) }
            .reduce(ReaderIntent.Refresh(0))
        assertEquals(ReaderPageLoadState.Loading(), next.loadState(0))
        assertTrue(next.loadState(1) is ReaderPageLoadState.Ready)
    }

    @Test
    fun `retry turns an error back into loading`() {
        val next = state(3)
            .withPage(1) { ReaderItemUi.Error(1, "boom") }
            .reduce(ReaderIntent.Retry(1))
        assertEquals(ReaderPageLoadState.Loading(), next.loadState(1))
    }

    @Test
    fun `progress only updates loading items`() {
        var next = state(2).reduce(ReaderIntent.Prefetch(listOf(0)))
        next = next.withProgress(0, 42)
        assertEquals(ReaderPageLoadState.Loading(42), next.loadState(0))
        assertEquals(ReaderPageLoadState.Queued, next.loadState(1))

        // 已经下好的页不能被进度事件冲掉
        next = next.withPage(0) { ReaderItemUi.Page(0, ReaderPageLoadState.Ready(File("/tmp/0"))) }
        next = next.withProgress(0, 99)
        assertTrue(next.loadState(0) is ReaderPageLoadState.Ready)
    }

    @Test
    fun `update settings replaces the whole object`() {
        val next = state(2).reduce(
            ReaderIntent.UpdateSettings(
                ReaderSettings(vertical = true, scaleMode = 1, autoPageInterval = 5),
            ),
        )
        assertTrue(next.settings.vertical)
        assertEquals(1, next.settings.scaleMode)
        assertEquals(5, next.settings.autoPageInterval)
        // 没给的字段回到默认值，不能是「半新半旧」
        assertFalse(next.settings.reverseLayout)
    }

    @Test
    fun `dim overlay only shows for custom lightness at or below 100`() {
        // 没开自定义亮度、或亮度 > 100（走 Window 加亮）都不该压暗
        assertEquals(0f, ReaderSettings().dimAlpha)
        assertEquals(0f, ReaderSettings(screenLightness = 50).dimAlpha)
        assertEquals(0f, ReaderSettings(customScreenLightness = true, screenLightness = 200).dimAlpha)

        val darkest = ReaderSettings(customScreenLightness = true, screenLightness = 0).dimAlpha
        assertEquals(0xde / 255f, darkest, 0.001f)
        val half = ReaderSettings(customScreenLightness = true, screenLightness = 50).dimAlpha
        assertTrue(half in 0f..darkest)
    }

    @Test
    fun `prefetch window covers the neighbours of the current page`() {
        assertEquals(listOf(0, 1, 2), prefetchWindow(0, 10))
        assertEquals(listOf(3, 4, 5, 6), prefetchWindow(4, 10))
        assertEquals(listOf(8, 9), prefetchWindow(9, 10))
        assertEquals(emptyList<Int>(), prefetchWindow(0, 0))
    }

    @Test
    fun `page step moves relative to the current page`() {
        assertEquals(4, state(10, currentPage = 3).reduce(ReaderIntent.PageStep(1)).currentPage)
        assertEquals(2, state(10, currentPage = 3).reduce(ReaderIntent.PageStep(-1)).currentPage)
        // 音量键的「一次翻几页」就是同一个意图的大步长
        assertEquals(6, state(10, currentPage = 3).reduce(ReaderIntent.PageStep(3)).currentPage)
    }

    @Test
    fun `page step is clamped at both ends`() {
        assertEquals(9, state(10, currentPage = 9).reduce(ReaderIntent.PageStep(1)).currentPage)
        assertEquals(0, state(10, currentPage = 0).reduce(ReaderIntent.PageStep(-1)).currentPage)
        assertEquals(0, state(10, currentPage = 1).reduce(ReaderIntent.PageStep(-5)).currentPage)
    }

    @Test
    fun `page step at the edge emits no scroll request`() {
        // 钳回原地就等于没动，不该再触发一次滚动（否则每一帧都会重复滚到同一页）
        assertNull(state(10, currentPage = 9).reduce(ReaderIntent.PageStep(1)).scrollRequest)
    }

    @Test
    fun `page step reuses the scroll request machinery`() {
        val next = state(10, currentPage = 3).reduce(ReaderIntent.PageStep(2))
        assertEquals(5, next.scrollRequest!!.page)
        assertEquals(1L, next.scrollRequest!!.id)
    }
}
