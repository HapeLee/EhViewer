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
package com.hippo.ehviewer.ui.compose.state

import com.hippo.ehviewer.EhApplication.Companion.galleryDetailCache
import com.hippo.ehviewer.client.data.GalleryCommentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 评论列表的单一数据来源。
 *
 * 旧实现里评论页改动完靠 `setResult(KEY_COMMENT_LIST)` 回传给详情页，
 * 一旦详情页被回收 / `findSceneByTag` 取不到就静默丢更新。
 * 这里改成「评论页写仓库，详情页重新进入时读仓库」：
 *
 * - 详情页 `onResume` 调 [latest]，比它手上那份新就覆盖；
 * - 其他订阅方（以后的 Compose 页面）可以直接收集 [revision]。
 *
 * 内存上限 [MAX_ENTRIES] 条，够覆盖来回翻的几页，不做持久化——评论本身
 * 随时可以从站点重新拉，落库反而会带来"评论被删了但本地还有"的一致性问题。
 */
object GalleryCommentsStore {
    private const val MAX_ENTRIES = 32

    private val _lists = MutableStateFlow<Map<Long, GalleryCommentList>>(emptyMap())

    /** gid → 最新评论；按写入顺序保留最近 [MAX_ENTRIES] 个 */
    val lists: StateFlow<Map<Long, GalleryCommentList>> = _lists.asStateFlow()

    /** 版本号，只用于让订阅方感知「有新写入」 */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun latest(gid: Long): GalleryCommentList? = _lists.value[gid]

    /**
     * 发布某个画廊的最新评论。
     *
     * 同时回写 `galleryDetailCache`，保证后面从缓存命中详情时看到的是同一份数据。
     */
    fun publish(gid: Long, list: GalleryCommentList) {
        if (gid == -1L) return
        _lists.update { current ->
            val merged = current + (gid to list)
            if (merged.size <= MAX_ENTRIES) {
                merged
            } else {
                merged.entries.drop(merged.size - MAX_ENTRIES).associate { it.key to it.value }
            }
        }
        _revision.update { it + 1 }
        galleryDetailCache.get(gid)?.comments = list
    }
}
