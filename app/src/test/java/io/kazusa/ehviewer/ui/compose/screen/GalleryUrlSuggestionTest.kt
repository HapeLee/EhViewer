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
package io.kazusa.ehviewer.ui.compose.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证搜索框粘贴链接后的跳转建议解析（旧 `SearchBar.SuggestionProvider` 的语义）。 */
class GalleryUrlSuggestionTest {
    @Test
    fun galleryDetailUrlBecomesDetailSuggestion() {
        val suggestion = parseUrlSuggestion("https://e-hentai.org/g/123456/abcdef1234/")
        assertEquals(UrlSuggestionUi.Detail(123456L, "abcdef1234"), suggestion)
    }

    @Test
    fun galleryPageUrlBecomesPageSuggestion() {
        val suggestion = parseUrlSuggestion("https://exhentai.org/s/abcdef1234/123456-3")
        assertEquals(UrlSuggestionUi.Page(123456L, "abcdef1234", 2), suggestion)
    }

    @Test
    fun plainKeywordHasNoSuggestion() {
        assertNull(parseUrlSuggestion("touhou"))
        assertNull(parseUrlSuggestion("artist:sample"))
    }

    @Test
    fun blankHasNoSuggestion() {
        assertNull(parseUrlSuggestion(""))
        assertNull(parseUrlSuggestion("   "))
    }

    @Test
    fun detailUrlWinsOverPageUrl() {
        // 同时含 `gid/token` 与 `token/page` 形态时，先按详情链接解析
        val suggestion = parseUrlSuggestion("https://e-hentai.org/g/654321/0123456789/")
        assertEquals(UrlSuggestionUi.Detail(654321L, "0123456789"), suggestion)
    }
}
