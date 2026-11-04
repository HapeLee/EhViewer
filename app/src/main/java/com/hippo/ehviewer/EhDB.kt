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
package com.hippo.ehviewer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.paging.PagingSource
import androidx.room.Room.databaseBuilder
import com.hippo.ehviewer.EhApplication.Companion.ehDatabase
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.BasicDao
import com.hippo.ehviewer.dao.DownloadDirname
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import com.hippo.ehviewer.dao.EhDatabase
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.dao.LocalFavoriteInfo
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.dao.ReadingProgress
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.unifile.UniFile
import com.hippo.util.sendTo

/**
 * Room 的对外门面。
 *
 * 线程约束：这里的每个方法都会碰 Room，必须在 IO 线程调用。P6-d 已经摘掉
 * `allowMainThreadQueries`，主线程访问会直接抛 `IllegalStateException`，不再只靠约定。
 *
 * 并发约束：过去靠 `@Synchronized` 把整个 EhDB 串行化，顺带保护「查一次 / 读一批 →
 * 改 → 写回」这类复合操作。现在把这些语义下沉到 DAO：
 * - 单行的 `@Upsert` / `@Insert(onConflict = IGNORE)` 由语句自身保证原子；
 * - 复合操作（下载标签与快速搜索重排、过滤器去重插入、收藏槽位更新）由 DAO 上标注
 *   `@Transaction` 的方法保证。
 * 进程内串行本身不再需要，Room 会自己处理 SQLite 的写锁。
 */
object EhDB {
    private val TAG = EhDB::class.java.simpleName
    private val db = ehDatabase

    /**
     * 注意：这里**不再**把 `STATE_WAIT` / `STATE_DOWNLOAD` 抹成 `STATE_NONE`。
     * 那两态就是持久队列（P6-f）：进程被杀时它们留在库里，下次起来由
     * `DownloadManager.restorePendingDownloads()` 重新排队。
     */
    val allDownloadInfo: List<DownloadInfo>
        get() = db.downloadsDao().list()

    fun updateDownloadInfo(downloadInfos: List<DownloadInfo>) {
        db.downloadsDao().update(downloadInfos)
    }

    fun putDownloadInfo(downloadInfo: DownloadInfo) {
        db.downloadsDao().upsert(downloadInfo)
    }

    fun removeDownloadInfo(downloadInfo: DownloadInfo) {
        db.downloadsDao().delete(downloadInfo)
    }

    val allDownloadDirname: List<DownloadDirname>
        get() = db.downloadDirnameDao().list()

    fun getDownloadDirname(gid: Long): String? = db.downloadDirnameDao().load(gid)?.dirname

    fun putDownloadDirname(gid: Long, dirname: String?) {
        db.downloadDirnameDao().upsert(DownloadDirname(gid, dirname))
    }

    fun removeDownloadDirname(gid: Long) {
        db.downloadDirnameDao().deleteByKey(gid)
    }

    val allDownloadLabelList: List<DownloadLabel>
        get() = db.downloadLabelDao().list()

    fun addDownloadLabel(label: String): DownloadLabel {
        val raw = DownloadLabel()
        raw.label = label
        raw.time = System.currentTimeMillis()
        raw.id = db.downloadLabelDao().insert(raw)
        return raw
    }

    fun addDownloadLabel(raw: DownloadLabel): DownloadLabel {
        // Reset id
        raw.id = null
        raw.id = db.downloadLabelDao().insert(raw)
        return raw
    }

    fun updateDownloadLabel(raw: DownloadLabel?) {
        db.downloadLabelDao().update(raw!!)
    }

    fun moveDownloadLabel(fromPosition: Int, toPosition: Int) {
        db.downloadLabelDao().move(fromPosition, toPosition)
    }

    fun removeDownloadLabel(raw: DownloadLabel?) {
        db.downloadLabelDao().delete(raw!!)
    }

    val allLocalFavorites: List<GalleryInfo>
        get() {
            val dao = db.localFavoritesDao()
            val list = dao.list()
            return ArrayList<GalleryInfo>(list)
        }

    fun searchLocalFavorites(query: String): List<GalleryInfo> {
        val dao = db.localFavoritesDao()
        val list = dao.list("%$query%")
        return ArrayList<GalleryInfo>(list)
    }

    fun removeLocalFavorites(gid: Long) {
        db.localFavoritesDao().deleteByKey(gid)
    }

    fun removeLocalFavorites(gidArray: LongArray) {
        val dao = db.localFavoritesDao()
        for (gid in gidArray) {
            dao.deleteByKey(gid)
        }
    }

    fun containLocalFavorites(gid: Long): Boolean = db.localFavoritesDao().contains(gid)

    fun putLocalFavorites(galleryInfo: GalleryInfo) {
        db.localFavoritesDao().insertOrIgnore(toLocalFavoriteInfo(galleryInfo))
    }

    fun putLocalFavorites(galleryInfoList: List<GalleryInfo>) {
        db.localFavoritesDao().insertOrIgnore(galleryInfoList.map(::toLocalFavoriteInfo))
    }

    private fun toLocalFavoriteInfo(galleryInfo: GalleryInfo): LocalFavoriteInfo = galleryInfo as? LocalFavoriteInfo ?: LocalFavoriteInfo(galleryInfo).apply {
        time = System.currentTimeMillis()
    }

    val allQuickSearch: List<QuickSearch>
        get() = db.quickSearchDao().list()

    fun insertQuickSearch(quickSearch: QuickSearch) {
        val dao = db.quickSearchDao()
        quickSearch.id = null
        quickSearch.time = System.currentTimeMillis()
        quickSearch.id = dao.insert(quickSearch)
    }

    fun importQuickSearch(quickSearchList: List<QuickSearch?>) {
        val dao = db.quickSearchDao()
        for (quickSearch in quickSearchList) {
            dao.insert(quickSearch!!)
        }
    }

    fun deleteQuickSearch(quickSearch: QuickSearch?) {
        quickSearch ?: return
        db.quickSearchDao().delete(quickSearch)
    }

    fun moveQuickSearch(fromPosition: Int, toPosition: Int) {
        db.quickSearchDao().move(fromPosition, toPosition)
    }

    /**
     * 交给 Paging 的 source：Room 生成的 PagingSource 是惰性的，创建时不碰数据库，
     * 真正的查询由 Paging 自己的 IO 线程发起，所以这里可以留在主线程。
     */
    val historyLazyList: PagingSource<Int, HistoryInfo>
        get() = db.historyDao().listLazy()

    /** 带关键字的版本：标题 / 原名 / 作者模糊匹配，同样惰性执行 */
    fun searchHistory(query: String): PagingSource<Int, HistoryInfo> = db.historyDao().searchLazy(query)

    /**
     * 画廊标题：只从历史里取。
     *
     * 阅读器的入口 route 只有 gid/token（见 `EhRoute.Reader`），而页源那头也拿不到标题
     * （`SpiderInfo` 没有 title 字段）。历史表是唯一「按 gid 就能查到标题」的地方，
     * 查不到就返回 null，顶栏退回页码显示——这只是装饰，不值得为它多发一次请求。
     */
    fun getGalleryTitle(gid: Long): String? {
        if (gid == -1L) return null
        return db.historyDao().load(gid)?.title?.takeIf { it.isNotBlank() }
    }

    /**
     * 读到第几页（0 基）；没有记录时返回 null。
     *
     * 入口带的页码优先于这里，与旧实现 `mPage >= 0 ? mPage : provider.startPage` 一致。
     */
    fun getReadingProgress(gid: Long): Int? = db.readingProgressDao().load(gid)?.page

    fun putReadingProgress(gid: Long, page: Int) {
        db.readingProgressDao().upsert(ReadingProgress(gid, page, System.currentTimeMillis()))
    }

    fun removeReadingProgress(gid: Long) {
        db.readingProgressDao().delete(gid)
    }

    fun putHistoryInfo(galleryInfo: GalleryInfo) {
        val info = galleryInfo as? HistoryInfo ?: HistoryInfo(galleryInfo)
        info.time = System.currentTimeMillis()
        db.historyDao().upsert(info)
    }

    fun updateHistoryFavSlot(gid: Long, slot: Int) {
        db.historyDao().updateFavoriteSlot(gid, slot)
    }

    fun putHistoryInfo(historyInfoList: List<HistoryInfo>) {
        db.historyDao().insertOrIgnore(historyInfoList)
    }

    fun deleteHistoryInfo(info: HistoryInfo?) {
        db.historyDao().delete(info!!)
    }

    fun clearHistoryInfo() {
        db.historyDao().deleteAll()
    }

    val allFilter: List<Filter>
        get() = db.filterDao().list()

    fun addFilter(filter: Filter): Boolean = try {
        db.filterDao().insertIfAbsent(filter.text!!, filter.mode, filter)
    } catch (_: Exception) {
        false
    }

    fun deleteFilter(filter: Filter) {
        db.filterDao().delete(filter)
    }

    fun triggerFilter(filter: Filter) {
        filter.enable = filter.enable?.not() == true
        db.filterDao().update(filter)
    }

    private fun <T> copyDao(from: BasicDao<T>, to: BasicDao<T>) {
        val list = from.list()
        for (item in list) to.insert(item)
    }

    fun exportDB(context: Context, uri: Uri): Boolean {
        val ehExportName = "eh.export.db"
        runCatching {
            // Delete old export db
            context.deleteDatabase(ehExportName)
            val newDb =
                databaseBuilder(context, EhDatabase::class.java, ehExportName).build()

            // Copy data to a export db
            copyDao(db.downloadsDao(), newDb.downloadsDao())
            copyDao(db.downloadLabelDao(), newDb.downloadLabelDao())
            copyDao(db.downloadDirnameDao(), newDb.downloadDirnameDao())
            copyDao(db.historyDao(), newDb.historyDao())
            copyDao(db.quickSearchDao(), newDb.quickSearchDao())
            copyDao(db.localFavoritesDao(), newDb.localFavoritesDao())
            copyDao(db.filterDao(), newDb.filterDao())

            // Close export db so we can copy it
            newDb.close()

            // Copy export db to data dir
            val dbFile = context.getDatabasePath(ehExportName)
            UniFile.fromFile(dbFile)!! sendTo UniFile.fromUri(context, uri)!!
            return true
        }.onFailure {
            Log.e(TAG, "Error", it)
        }
        return false
    }

    /**
     * @return error string, null for no error
     */
    // 调用方本来就在 IO（AdvancedFragment 的 launchIO），所以直接 suspend；
    // 导入是一次性的独占操作，不再需要额外的互斥
    suspend fun importDB(context: Context, uri: Uri): String? {
        val tmpDBName = "tmp.db"
        runCatching {
            val oldDB = databaseBuilder(context, EhDatabase::class.java, tmpDBName)
                .createFromInputStream { context.contentResolver.openInputStream(uri) }.build()
            // Download label
            val manager = DownloadManager
            runCatching {
                val downloadLabelList = oldDB.downloadLabelDao().list()
                manager.addDownloadLabel(downloadLabelList)
            }
            // Downloads
            runCatching {
                val downloadInfoList = oldDB.downloadsDao().list()
                manager.addDownload(downloadInfoList, false)
            }
            // Download dirname
            runCatching {
                oldDB.downloadDirnameDao().list().forEach {
                    putDownloadDirname(it.gid, it.dirname)
                }
            }
            // History
            runCatching {
                val historyInfoList = oldDB.historyDao().list()
                putHistoryInfo(historyInfoList)
            }
            // QuickSearch
            runCatching {
                val quickSearchList = oldDB.quickSearchDao().list()
                val currentQuickSearchList = db.quickSearchDao().list()
                val importList = quickSearchList.mapNotNull { newQS ->
                    newQS.takeIf { currentQuickSearchList.find { it.name == newQS.name } == null }
                }
                importQuickSearch(importList)
            }
            // LocalFavorites
            runCatching {
                oldDB.localFavoritesDao().list().forEach {
                    putLocalFavorites(it)
                }
            }
            // Filter
            runCatching {
                val filterList = oldDB.filterDao().list()
                val currentFilterList = db.filterDao().list()
                filterList.forEach {
                    if (it !in currentFilterList) addFilter(it)
                }
            }
            oldDB.close()
            context.deleteDatabase(tmpDBName)
        }.onFailure {
            Log.e(TAG, "Error", it)
            return context.getString(R.string.settings_advanced_import_data_cant_read)
        }
        return null
    }
}
