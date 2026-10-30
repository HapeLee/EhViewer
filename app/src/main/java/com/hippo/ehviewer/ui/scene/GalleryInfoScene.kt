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

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.screen.GalleryInfoScreen
import com.hippo.ehviewer.ui.compose.screen.InfoAction
import com.hippo.ehviewer.ui.compose.screen.InfoRow
import com.hippo.util.addTextToClipboard
import com.hippo.util.getParcelableCompat

/**
 * 画廊信息页：把 [GalleryDetail] 摊成键值列表。
 *
 * 迁移点：
 * - 行行为不再靠下标（`INDEX_URL = 3` / `INDEX_PARENT = 10`），改由每行携带 [InfoAction]
 * - 表头行、两种 item 布局、`LinearDividerItemDecoration` 全部移除
 */
class GalleryInfoScene : EhComposeScene() {

    private var rows: List<InfoRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = buildRows(arguments)
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.GalleryInfo> {
            GalleryInfoScreen(
                title = getString(R.string.gallery_info),
                rows = rows,
                onBack = { finish() },
                onCopy = { value, remember ->
                    mainActivity?.addTextToClipboard(value, false)
                    if (remember) Settings.putClipboardTextHashCode(value.hashCode())
                },
                onOpenUrl = { url -> context?.let { UrlOpener.openUrl(it, url, true) } },
            )
        }
    }

    private fun buildRows(args: Bundle?): List<InfoRow> {
        val detail = args?.getParcelableCompat<GalleryDetail>(KEY_GALLERY_DETAIL) ?: return emptyList()
        return listOfNotNull(
            row(R.string.key_gid, detail.gid.toString()),
            row(R.string.key_token, detail.token),
            row(R.string.key_url, EhUrl.getGalleryDetailUrl(detail.gid, detail.token), InfoAction.COPY_AND_REMEMBER),
            row(R.string.key_title, detail.title),
            row(R.string.key_title_jpn, detail.titleJpn),
            row(R.string.key_thumb, detail.thumbUrl),
            row(R.string.key_category, EhUtils.getCategory(detail.category)),
            row(R.string.key_uploader, detail.uploader),
            row(R.string.key_posted, detail.posted),
            row(R.string.key_parent, detail.parent, InfoAction.OPEN_URL),
            row(R.string.key_visible, detail.visible),
            row(R.string.key_language, detail.language),
            row(R.string.key_pages, detail.pages.toString()),
            row(R.string.key_size, detail.size),
            row(R.string.key_favorite_count, detail.favoriteCount.toString()),
            row(R.string.key_favorited, java.lang.Boolean.toString(detail.isFavorited)),
            row(R.string.key_favorite_name, detail.favoriteName),
            row(R.string.key_rating_count, detail.ratingCount.toString()),
            row(R.string.key_rating, detail.rating.toString()),
            row(R.string.key_torrents, detail.torrentCount.toString()),
            row(R.string.key_torrent_url, detail.torrentUrl),
        )
    }

    private fun row(labelRes: Int, value: String?, action: InfoAction? = null): InfoRow = InfoRow(
        label = getString(labelRes),
        value = value,
        action = if (value.isNullOrBlank()) null else action,
    )

    companion object {
        const val KEY_GALLERY_DETAIL = "gallery_detail"
    }
}
