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

import io.kazusa.ehviewer.client.EhUtils
import io.kazusa.ehviewer.client.data.ListUrlBuilder
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * route 层的回归护栏：只覆盖不依赖 Android 的纯逻辑，
 * 也就是「路由参数往返」和「查询条件映射」两块。
 */
class EhRouteCodecTest {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    private fun roundTrip(route: EhRoute): EhRoute = cbor.decodeFromByteArray(serializer<EhRoute>(), cbor.encodeToByteArray(serializer<EhRoute>(), route))

    @Test
    fun `route survives serialization round trip`() {
        val routes = listOf(
            EhRoute.SignIn(),
            EhRoute.Downloads(),
            EhRoute.GalleryDetail(gid = 1234567L, token = "deadbeef", page = 3),
            EhRoute.GalleryComments(gid = 42L, apiUid = 777L, apiKey = "abc", token = "t"),
            EhRoute.GalleryPreviews(gid = 42L, scrollTo = -1),
            EhRoute.Progress(gid = 42L, pToken = "pt", page = 7),
            EhRoute.Reader(gid = 42L, token = "t", page = 7, source = ReaderSource.DOWNLOAD),
            EhRoute.GalleryList(
                GalleryQuery(
                    mode = ListUrlBuilder.MODE_TAG,
                    category = EhUtils.MANGA,
                    keyword = "artist:someone",
                    advanceSearch = 4,
                    minRating = 3,
                    pageFrom = 10,
                    pageTo = 20,
                    hash = "sha1",
                ),
            ),
        )
        for (route in routes) {
            assertEquals(route, roundTrip(route))
        }
    }

    @Test
    fun `gallery query round trips through ListUrlBuilder`() {
        val queries = listOf(
            GalleryQuery(),
            GalleryQuery(
                action = LIST_ACTION_HOMEPAGE,
                mode = ListUrlBuilder.MODE_SUBSCRIPTION,
            ),
            GalleryQuery(
                mode = ListUrlBuilder.MODE_WHATS_HOT,
                category = EhUtils.NON_H,
                advanceSearch = 0,
            ),
            GalleryQuery(
                mode = ListUrlBuilder.MODE_TOPLIST,
                keyword = "13",
            ),
            GalleryQuery(
                mode = ListUrlBuilder.MODE_IMAGE_SEARCH,
                hash = "0123456789abcdef",
            ),
            GalleryQuery(
                mode = ListUrlBuilder.MODE_NORMAL,
                keyword = "full color",
                minRating = 4,
                pageFrom = 1,
                pageTo = 50,
            ),
        )
        for (query in queries) {
            val expected = query.copy(action = LIST_ACTION_LIST_URL_BUILDER)
            assertEquals(expected, galleryQueryOf(query.toListUrlBuilder()))
        }
    }

    @Test
    fun `uploader keyword is stored without duplicated prefix`() {
        val lub = ListUrlBuilder(ListUrlBuilder.MODE_UPLOADER).apply { keyword = "someone" }
        // ListUrlBuilder.keyword 的 getter 会带 uploader: 前缀，编码时必须剥掉
        assertEquals("uploader:someone", lub.keyword)

        val query = galleryQueryOf(lub)
        assertEquals("someone", query.keyword)

        val restored = query.toListUrlBuilder()
        assertEquals(ListUrlBuilder.MODE_UPLOADER, restored.mode)
        assertEquals("uploader:someone", restored.keyword)
    }

    @Test
    fun `gid is only exposed for routes that point at a gallery`() {
        assertEquals(42L, gidOf(EhRoute.GalleryDetail(gid = 42L)))
        assertEquals(42L, gidOf(EhRoute.GalleryComments(gid = 42L)))
        assertEquals(42L, gidOf(EhRoute.Reader(gid = 42L)))
        assertNull(gidOf(EhRoute.GalleryDetail()))
        assertNull(gidOf(EhRoute.SignIn()))
        assertNull(gidOf(EhRoute.GalleryList()))
    }

    @Test
    fun `archive reader keeps its uri in the route`() {
        val reader = EhRoute.Reader(uri = "content://media/picker/42", source = ReaderSource.ARCHIVE)
        assertEquals(reader, roundTrip(reader))
        assertEquals(ReaderSource.ARCHIVE, reader.source)
        // 压缩包没有 gid/token，别拿 -1 当画廊用
        assertNull(gidOf(reader))
    }

    @Test
    fun `reader is a gallery page without drawer and without start page by default`() {
        val reader = EhRoute.Reader()
        assertEquals(-1, reader.page)
        assertEquals(ReaderSource.ONLINE, reader.source)
        assertNull(gidOf(reader))

        // 阅读器占用整屏：抽屉要锁掉，否则翻页手势会被它抢走
        assertFalse(reader.hasLeftDrawer)
        assertFalse(isSolidRoute(reader))
        assertNull(targetOf(reader))
    }
}
