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

import io.kazusa.ehviewer.client.parser.GalleryDetailUrlParser
import io.kazusa.ehviewer.client.parser.GalleryPageUrlParser

/**
 * 搜索框内容能解析成画廊链接时给出的跳转建议。
 *
 * [Detail] 对应画廊详情链接（`/g/123456/abcdef1234/`），点击进详情页；
 * [Page] 对应分页链接（`/s/abcdef1234/123456-3`），点击进阅读进度页。
 */
sealed interface UrlSuggestionUi {
    data class Detail(val gid: Long, val token: String?) : UrlSuggestionUi
    data class Page(val gid: Long, val pToken: String?, val page: Int) : UrlSuggestionUi
}

/**
 * 把搜索框里的文本解析成跳转建议，语义与旧 `GalleryListScene.setSearchBarSuggestionProvider` 一致：
 * 先按画廊详情链接解析，再按分页链接解析，都不匹配就返回 null（此时只做普通搜索）。
 *
 * 抽成顶层纯函数是为了能直接单测——旧实现写在 `SearchBar.SuggestionProvider` 里，没法测。
 */
fun parseUrlSuggestion(text: String): UrlSuggestionUi? {
    if (text.isBlank()) return null
    GalleryDetailUrlParser.parse(text, false)?.let {
        return UrlSuggestionUi.Detail(it.gid, it.token)
    }
    GalleryPageUrlParser.parse(text, false)?.let {
        return UrlSuggestionUi.Page(it.gid, it.pToken, it.page)
    }
    return null
}
