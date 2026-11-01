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
interface FilterDao : BasicDao<Filter> {
    @Query("SELECT * FROM `FILTER`")
    override fun list(): List<Filter>

    @Update
    fun update(filter: Filter)

    @Insert
    override fun insert(t: Filter): Long

    /**
     * 过滤器的「唯一」是业务上的（TEXT + MODE），不是主键，所以只能靠事务把
     * 「先查后插」包起来；插入成功返回 true，已存在返回 false。
     */
    @Transaction
    fun insertIfAbsent(text: String, mode: Int, filter: Filter): Boolean {
        if (load(text, mode) != null) return false
        filter.id = null
        filter.id = insert(filter)
        return true
    }

    @Delete
    fun delete(filter: Filter)

    @Query("SELECT * FROM `FILTER` WHERE TEXT = :text AND MODE = :mode")
    fun load(text: String, mode: Int): Filter?
}
