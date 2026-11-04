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

import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.AdvanceSearchFlags
import com.hippo.ehviewer.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 验证搜索面板的条件装配与校验（原 `SearchLayout.formatListUrlBuilder` 的语义）。
 */
class SearchPanelStateTest {
    @Test
    fun emptyAdvanceFieldsDisableAdvanceSearch() {
        val state = SearchPanelState(
            searchMode = ListUrlBuilder.MODE_NORMAL,
            category = EhUtils.DOUJINSHI,
            language = 1,
        )
        val criteria = state.buildCriteria("query") as SearchCriteria.Success
        assertEquals(ListUrlBuilder.MODE_NORMAL, criteria.mode)
        assertEquals(EhUtils.DOUJINSHI, criteria.category)
        assertEquals(1, criteria.language)
        assertEquals("query", criteria.keyword)
        assertEquals(-1, criteria.advanceSearch)
        assertEquals(-1, criteria.minRating)
        assertEquals(-1, criteria.pageFrom)
        assertEquals(-1, criteria.pageTo)
    }

    @Test
    fun anyAdvanceFieldEnablesAdvanceSearch() {
        // 不再有独立开关：星级、页数、过滤开关任意一项非空就带 advsearch
        val state = SearchPanelState(
            minRating = 3,
            pageFrom = "20",
            pageTo = "200",
        )
        val criteria = state.buildCriteria(null) as SearchCriteria.Success
        assertEquals(0, criteria.advanceSearch)
        assertEquals(3, criteria.minRating)
        assertEquals(20, criteria.pageFrom)
        assertEquals(200, criteria.pageTo)

        val flagOnly = SearchPanelState(advanceSearch = AdvanceSearchFlags.SH or AdvanceSearchFlags.SFT)
            .buildCriteria(null) as SearchCriteria.Success
        assertEquals(AdvanceSearchFlags.SH or AdvanceSearchFlags.SFT, flagOnly.advanceSearch)
        assertEquals(-1, flagOnly.minRating)
    }

    @Test
    fun pageRangeValidationMatchesReference() {
        // 只填最大值：至少 10
        assertEquals(R.string.search_sp_err1, validatePageRange(0, 9))
        assertEquals(0, validatePageRange(0, 10))
        // 两端都填：最小值不得超过 min(max / 2, max - 20)
        assertEquals(0, validatePageRange(10, 40))
        assertEquals(R.string.search_sp_err2, validatePageRange(10, 25))
        assertEquals(R.string.search_sp_err2, validatePageRange(40, 60))
        // 都不填：不校验
        assertEquals(0, validatePageRange(0, 0))
    }

    @Test
    fun pageRangeClampedOnSubmit() {
        // 提交侧把输入收敛到 0..1000 / 0..2000，超界不再报错
        val criteria = SearchPanelState(pageFrom = "1001", pageTo = "3000").buildCriteria(null) as SearchCriteria.Success
        assertEquals(1000, criteria.pageFrom)
        assertEquals(2000, criteria.pageTo)
    }

    @Test
    fun invalidPageRangeFailsSubmit() {
        val failure = SearchPanelState(pageFrom = "10", pageTo = "25").buildCriteria(null) as SearchCriteria.Failure
        assertEquals(R.string.search_sp_err2, failure.messageRes)
    }

    @Test
    fun categoryCheckedMeansIncluded() {
        val state = SearchPanelState(category = EhUtils.NONE)
        // EhUtils.NONE 等价于「全部包含」
        assertTrue(state.isCategoryChecked(EhUtils.DOUJINSHI))
        state.toggleCategory(EhUtils.MANGA)
        assertTrue(!state.isCategoryChecked(EhUtils.MANGA))
        assertEquals(0, state.category and EhUtils.MANGA)
        assertTrue(state.isCategoryChecked(EhUtils.DOUJINSHI))
        // 再点一次恢复包含
        state.toggleCategory(EhUtils.MANGA)
        assertTrue(state.isCategoryChecked(EhUtils.MANGA))
    }

    @Test
    fun longPressReversesOtherCategories() {
        val state = SearchPanelState(category = EhUtils.NONE)
        // 长按一个「已包含」的分类：其余分类统一变成不包含
        state.reverseOthers(EhUtils.MANGA)
        assertTrue(state.isCategoryChecked(EhUtils.MANGA))
        assertTrue(!state.isCategoryChecked(EhUtils.DOUJINSHI))
        assertTrue(!state.isCategoryChecked(EhUtils.MISC))
    }

    @Test
    fun searchModeComesFromEntry() {
        // 入口带进来的模式作为默认值
        val state = SearchPanelState(searchMode = ListUrlBuilder.MODE_UPLOADER)
        val criteria = state.buildCriteria("someone") as SearchCriteria.Success
        assertEquals(ListUrlBuilder.MODE_UPLOADER, criteria.mode)
    }

    @Test
    fun searchModeChipSwitchIsSubmitted() {
        // 第三行的模式 chip 直接改 state.searchMode，提交时用它
        val state = SearchPanelState()
        assertEquals(ListUrlBuilder.MODE_NORMAL, state.searchMode)
        state.searchMode = ListUrlBuilder.MODE_TAG
        val criteria = state.buildCriteria("tag") as SearchCriteria.Success
        assertEquals(ListUrlBuilder.MODE_TAG, criteria.mode)
    }
}
