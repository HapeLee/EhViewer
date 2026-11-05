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
package io.kazusa.ehviewer.ui.compose.nav

import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P7-e 之后「跨任务打开宿主」靠 route 的 CBOR 载荷，不再有「场景类名 + arguments」。
 *
 * 这里锁住三件事：
 * 1. 载荷能往返（[encodeRoute] / [decodeRoute] 与 Nav3 保存返回栈用同一套序列化）；
 * 2. 坏载荷安静地退化成 `null`，由宿主回落到初始页，而不是崩在启动路径上；
 * 3. 抽屉勾选项按列表模式推导（原 `BaseScene.getNavCheckedItem()`）。
 */
class EhRouteTransportTest {
    @Test
    fun `route payload survives intent transport`() {
        val routes = listOf(
            EhRoute.Favorites,
            EhRoute.History,
            EhRoute.Downloads(gid = 42L, clearService = true),
            EhRoute.GalleryDetail(gid = 42L, token = "t", page = 2),
            EhRoute.GalleryInfo(gid = 42L),
            EhRoute.GalleryComments(gid = 42L, apiUid = 7L, apiKey = "k", token = "t"),
            EhRoute.GalleryPreviews(gid = 42L, scrollTo = -1),
            EhRoute.Progress(gid = 42L, pToken = "pt", page = 3),
            EhRoute.GalleryList(
                GalleryQuery(mode = ListUrlBuilder.MODE_TAG, keyword = "artist:someone"),
            ),
        )
        for (route in routes) {
            assertEquals(route, decodeRoute(encodeRoute(route)))
        }
    }

    @Test
    fun `broken payload decodes to null instead of throwing`() {
        assertNull(decodeRoute(byteArrayOf(1, 2, 3, 4)))
        assertNull(decodeRoute(ByteArray(0)))
    }

    @Test
    fun `nav checked item follows list mode`() {
        assertEquals(R.id.nav_homepage, listRoute(ListUrlBuilder.MODE_NORMAL).navCheckedItem)
        assertEquals(R.id.nav_subscription, listRoute(ListUrlBuilder.MODE_SUBSCRIPTION).navCheckedItem)
        assertEquals(R.id.nav_whats_hot, listRoute(ListUrlBuilder.MODE_WHATS_HOT).navCheckedItem)
        assertEquals(R.id.nav_toplist, listRoute(ListUrlBuilder.MODE_TOPLIST).navCheckedItem)
        // 图片搜索 / tag 列表不是抽屉入口，不勾任何一项
        assertEquals(0, listRoute(ListUrlBuilder.MODE_IMAGE_SEARCH).navCheckedItem)
        assertEquals(0, listRoute(ListUrlBuilder.MODE_UPLOADER).navCheckedItem)
    }

    @Test
    fun `only drawer entries are checked`() {
        assertEquals(R.id.nav_favourite, EhRoute.Favorites.navCheckedItem)
        assertEquals(R.id.nav_history, EhRoute.History.navCheckedItem)
        assertEquals(R.id.nav_downloads, EhRoute.Downloads().navCheckedItem)
        assertEquals(0, EhRoute.SignIn().navCheckedItem)
        assertEquals(0, EhRoute.GalleryDetail(gid = 1L).navCheckedItem)
    }

    private fun listRoute(mode: Int): EhRoute = EhRoute.GalleryList(GalleryQuery(mode = mode))
}
