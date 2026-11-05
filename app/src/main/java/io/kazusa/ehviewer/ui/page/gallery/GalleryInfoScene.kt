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
package io.kazusa.ehviewer.ui.page.gallery

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.UrlOpener
import io.kazusa.ehviewer.client.EhUrl
import io.kazusa.ehviewer.client.EhUtils
import io.kazusa.ehviewer.client.data.GalleryDetail
import io.kazusa.ehviewer.client.thumbUrl
import io.kazusa.ehviewer.support.util.addTextToClipboard
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.cachedGalleryDetail
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.screen.GalleryInfoScreen
import io.kazusa.ehviewer.ui.compose.screen.InfoAction
import io.kazusa.ehviewer.ui.compose.screen.InfoRow

/**
 * 画廊信息页：把 [GalleryDetail] 摊成键值列表。
 *
 * P7-c 出壳后不再经 `arguments` 传整块 `GalleryDetail`：route 只带 `gid`，
 * 详情从 `galleryDetailCache` 取（详情页加载成功时会写进去）。
 * 与出壳前一致——缓存未命中就是空列表，不额外发请求。
 *
 * 行行为不再靠下标（`INDEX_URL = 3` / `INDEX_PARENT = 10`），改由每行携带 [InfoAction]。
 */
@Composable
fun GalleryInfoScene(route: EhRoute.GalleryInfo) {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val detail = remember(route.gid) { cachedGalleryDetail(route) }
    val rows = remember(detail) { detail?.let { buildInfoRows(context, it) }.orEmpty() }

    GalleryInfoScreen(
        title = stringResource(R.string.gallery_info),
        rows = rows,
        onBack = { navigator.pop() },
        onCopy = { value, rememberHash ->
            context.addTextToClipboard(value, false)
            // 记住哈希，避免宿主把这条链接又当成「需要搜索的画廊」再弹一次
            if (rememberHash) Settings.putClipboardTextHashCode(value.hashCode())
        },
        onOpenUrl = { url -> UrlOpener.openUrl(context, url, true) },
    )
}

private fun buildInfoRows(context: Context, detail: GalleryDetail): List<InfoRow> = listOf(
    infoRow(context, R.string.key_gid, detail.gid.toString()),
    infoRow(context, R.string.key_token, detail.token),
    infoRow(
        context,
        R.string.key_url,
        EhUrl.getGalleryDetailUrl(detail.gid, detail.token),
        InfoAction.COPY_AND_REMEMBER,
    ),
    infoRow(context, R.string.key_title, detail.title),
    infoRow(context, R.string.key_title_jpn, detail.titleJpn),
    infoRow(context, R.string.key_thumb, detail.thumbUrl),
    infoRow(context, R.string.key_category, EhUtils.getCategory(detail.category)),
    infoRow(context, R.string.key_uploader, detail.uploader),
    infoRow(context, R.string.key_posted, detail.posted),
    infoRow(context, R.string.key_parent, detail.parent, InfoAction.OPEN_URL),
    infoRow(context, R.string.key_visible, detail.visible),
    infoRow(context, R.string.key_language, detail.language),
    infoRow(context, R.string.key_pages, detail.pages.toString()),
    infoRow(context, R.string.key_size, detail.size),
    infoRow(context, R.string.key_favorite_count, detail.favoriteCount.toString()),
    infoRow(context, R.string.key_favorited, detail.isFavorited.toString()),
    infoRow(context, R.string.key_favorite_name, detail.favoriteName),
    infoRow(context, R.string.key_rating_count, detail.ratingCount.toString()),
    infoRow(context, R.string.key_rating, detail.rating.toString()),
    infoRow(context, R.string.key_torrents, detail.torrentCount.toString()),
    infoRow(context, R.string.key_torrent_url, detail.torrentUrl),
)

/** 空值行不挂动作，与出壳前「值不存在就点不动」保持一致 */
private fun infoRow(
    context: Context,
    labelRes: Int,
    value: String?,
    action: InfoAction? = null,
): InfoRow = InfoRow(
    label = context.getString(labelRes),
    value = value,
    action = if (value.isNullOrBlank()) null else action,
)
