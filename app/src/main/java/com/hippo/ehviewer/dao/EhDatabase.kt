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

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookmarkInfo::class, DownloadInfo::class, DownloadLabel::class, DownloadDirname::class, Filter::class, HistoryInfo::class, LocalFavoriteInfo::class, QuickSearch::class, ReadingProgress::class],
    version = 5,
    exportSchema = true,
)
abstract class EhDatabase : RoomDatabase() {
    abstract fun bookmarksBao(): BookmarksDao
    abstract fun downloadDirnameDao(): DownloadDirnameDao
    abstract fun downloadLabelDao(): DownloadLabelDao
    abstract fun downloadsDao(): DownloadsDao
    abstract fun filterDao(): FilterDao
    abstract fun historyDao(): HistoryDao
    abstract fun localFavoritesDao(): LocalFavoritesDao
    abstract fun quickSearchDao(): QuickSearchDao
    abstract fun readingProgressDao(): ReadingProgressDao
}

/**
 * 4 → 5：只加一张 [ReadingProgress] 表。
 *
 * 建库时挂了 `fallbackToDestructiveMigration`，缺迁移就会整库重建（历史、下载全丢）。
 * 所以每次动 schema 都必须显式补一条迁移，别指望那个兜底。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `READING_PROGRESS` " +
                "(`GID` INTEGER NOT NULL, `PAGE` INTEGER NOT NULL, `TIME` INTEGER NOT NULL, PRIMARY KEY(`GID`))",
        )
    }
}

fun buildMainDB(context: Context): EhDatabase {
    // 不开 allowMainThreadQueries：所有 DAO 只经 EhDB 出去，而 EhDB 的调用点（P6-a / P6-c）
    // 已经全部搬到 IO 线程/协程里了。Room 会在主线程访问时直接抛异常，别再把它开回去
    return Room.databaseBuilder(context, EhDatabase::class.java, "eh.db")
        .addMigrations(MIGRATION_4_5)
        // 按“新项目”口径处理：不背历史迁移链，schema 变动时整库重建
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
}
