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
package io.kazusa.ehviewer.client

import android.text.TextUtils
import android.util.Log
import io.kazusa.ehviewer.client.parser.GalleryDetailUrlParser
import io.kazusa.ehviewer.client.parser.GalleryListUrlParser
import io.kazusa.ehviewer.client.parser.GalleryPageUrlParser
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.galleryQueryOf

/**
 * 外部链接 → route。
 *
 * 以前返回 `Announcer`（类名 + Bundle），现在直接给类型化路由：
 * 链接能表达的就这三种（列表 / 详情 / 分页），够预先一眼看全。
 */
object EhUrlOpener {
    private val TAG = EhUrlOpener::class.java.simpleName

    fun parseUrl(url: String): EhRoute? {
        if (TextUtils.isEmpty(url)) {
            return null
        }
        val listUrlBuilder = GalleryListUrlParser.parse(url)
        if (listUrlBuilder != null) {
            return EhRoute.GalleryList(galleryQueryOf(listUrlBuilder))
        }
        val result1 = GalleryDetailUrlParser.parse(url)
        if (result1 != null) {
            return EhRoute.GalleryDetail(gid = result1.gid, token = result1.token)
        }
        val result2 = GalleryPageUrlParser.parse(url)
        if (result2 != null) {
            return EhRoute.Progress(
                gid = result2.gid,
                pToken = result2.pToken,
                page = result2.page,
            )
        }
        Log.i(TAG, "Can't parse url: $url")
        return null
    }
}
