/*
 * Copyright 2022 Moedog
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
package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 阅读进度：某个画廊读到第几页。
 *
 * 单独一张表而不是给 `HISTORY` 加列：历史是「访问过什么」，进度是「读到哪一页」，
 * 生命周期不一样——清历史不该顺手把进度清掉，反之亦然。
 *
 * 之前进度只活在 `savedInstanceState`（`KEY_PAGE` / `KEY_CURRENT_INDEX`）与
 * `SpiderInfo.startPage`（跟着缓存目录，清缓存就没），R4 起落到这里。
 */
@Entity(tableName = "READING_PROGRESS")
data class ReadingProgress(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    var gid: Long = 0,
    @ColumnInfo(name = "PAGE")
    var page: Int = 0,
    @ColumnInfo(name = "TIME")
    var time: Long = 0,
)
