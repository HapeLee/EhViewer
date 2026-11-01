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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface DownloadLabelDao : BasicDao<DownloadLabel> {
    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC")
    override fun list(): List<DownloadLabel>

    @Query("SELECT * FROM DOWNLOAD_LABELS ORDER BY TIME ASC LIMIT :limit OFFSET :offset")
    fun list(offset: Int, limit: Int): List<DownloadLabel>

    @Update
    fun update(downloadLabels: List<DownloadLabel>)

    @Update
    fun update(downloadLabel: DownloadLabel)

    @Insert
    override fun insert(t: DownloadLabel): Long

    @Delete
    fun delete(downloadLabel: DownloadLabel)

    /**
     * 把 [fromPosition] 上的标签挪到 [toPosition]：重排区间内所有行的 TIME。
     * 「读一批 → 改 → 写回」必须在一个事务里，否则并发拖动会写出互相覆盖的时间。
     */
    @Transaction
    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val reverse = fromPosition > toPosition
        val offset = if (reverse) toPosition else fromPosition
        val limit = if (reverse) fromPosition - toPosition + 1 else toPosition - fromPosition + 1
        val list = list(offset, limit)
        val step = if (reverse) 1 else -1
        val start = if (reverse) limit - 1 else 0
        val end = if (reverse) 0 else limit - 1
        val toTime = list[end].time
        var i = end
        while (if (reverse) i < start else i > 0) {
            val aTime = list[i].time
            val bTime = list[i + step].time
            list[i].time = if (aTime == bTime) bTime + step else bTime
            i += step
        }
        list[start].time = toTime
        update(list)
    }
}
