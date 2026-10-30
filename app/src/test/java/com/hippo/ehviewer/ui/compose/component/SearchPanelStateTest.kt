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
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.widget.AdvanceSearchTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 验证搜索面板的条件装配与校验（原 `SearchLayout.formatListUrlBuilder` 的语义）。
 */
class SearchPanelStateTest {
    @Test
    fun advanceDisabledIgnoresAdvanceFields() {
        val state = SearchPanelState(
            searchMode = ListUrlBuilder.MODE_NORMAL,
            category = EhUtils.DOUJINSHI,
            advanceEnabled = false,
            advanceSearch = AdvanceSearchTable.SH,
            minRating = 4,
            pageFrom = "10",
            pageTo = "900",
        )
        val criteria = state.buildCriteria("query") as SearchCriteria.Success
        assertEquals(ListUrlBuilder.MODE_NORMAL, criteria.mode)
        assertEquals(EhUtils.DOUJINSHI, criteria.category)
        assertEquals("query", criteria.keyword)
        assertEquals(-1, criteria.advanceSearch)
        assertEquals(-1, criteria.minRating)
        assertEquals(-1, criteria.pageFrom)
        assertEquals(-1, criteria.pageTo)
    }

    @Test
    fun advanceEnabledKeepsFields() {
        val state = SearchPanelState(
            advanceEnabled = true,
            advanceSearch = AdvanceSearchTable.SH or AdvanceSearchTable.SFT,
            minRating = 3,
            pageFrom = "20",
            pageTo = "200",
        )
        val criteria = state.buildCriteria(null) as SearchCriteria.Success
        assertEquals(AdvanceSearchTable.SH or AdvanceSearchTable.SFT, criteria.advanceSearch)
        assertEquals(3, criteria.minRating)
        assertEquals(20, criteria.pageFrom)
        assertEquals(200, criteria.pageTo)
    }

    @Test
    fun pageRangeValidationMatchesLegacyMessages() {
        assertEquals(
            R.string.search_sp_err0,
            (SearchPanelState(advanceEnabled = true, pageFrom = "1001").buildCriteria(null) as SearchCriteria.Failure).messageRes,
        )
        assertEquals(
            R.string.search_sp_err1,
            (SearchPanelState(advanceEnabled = true, pageTo = "9").buildCriteria(null) as SearchCriteria.Failure).messageRes,
        )
        assertEquals(
            R.string.search_sp_err2,
            (SearchPanelState(advanceEnabled = true, pageFrom = "10", pageTo = "25").buildCriteria(null) as SearchCriteria.Failure).messageRes,
        )
        assertEquals(
            R.string.search_sp_err3,
            (SearchPanelState(advanceEnabled = true, pageFrom = "40", pageTo = "60").buildCriteria(null) as SearchCriteria.Failure).messageRes,
        )
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
    fun imageTabRequiresImage() {
        val state = SearchPanelState(tab = SearchPanelTab.IMAGE)
        val failure = state.buildCriteria(null) as SearchCriteria.Failure
        assertEquals(R.string.select_image_first, failure.messageRes)
    }
}
