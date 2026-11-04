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
package com.hippo.ehviewer.ui.page.favorites

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.parser.FavoritesParser
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.screen.FavCategoryItem
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG
import com.hippo.util.ExceptionUtils
import com.hippo.util.launchIO
import com.hippo.util.toEpochMillis
import com.hippo.util.toLocalDateTime
import com.hippo.util.withUIContext
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * 收藏页的状态与网络逻辑。
 *
 * P7-c 出壳时从 `FavoritesScene`（Fragment）搬过来：页面不再是 Fragment，
 * 但「分页 + 修改收藏」这套流程需要活过旋转，所以托管给 ViewModel
 * （Nav3 的 `rememberViewModelStoreNavEntryDecorator` 保证离开返回栈时才 clear）。
 *
 * 关键约定与旧实现一致：
 * - `nextIndex` 承接服务端 `next`，`prev=1-0` 表示最后一页，
 *   `seek` 用日期字符串（等价于旧 `GalleryInfoContentHelper.goTo`）
 * - 修改收藏走「置 `enableModify` → 下一次刷新先发修改请求」，
 *   因此列表刷新与修改结果仍然只走一次网络往返
 * - `items` / `selection` 用 `neverEqualPolicy`：`GalleryInfo` 是可变对象，
 *   收藏位变化时列表引用不变，按值比较会漏刷新
 */
class FavoritesViewModel(private val appContext: Context) : ViewModel() {

    private var urlBuilder = FavListUrlBuilder()
    private var favCatArray: Array<String>? = Settings.favCat
    private var favCountArray: IntArray? = Settings.favCount
    private var favLocalCount = Settings.favLocalCount
    private var favCountSum = Settings.favCloudCount

    var items by mutableStateOf(emptyList<GalleryInfo>(), neverEqualPolicy())
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var searchHint by mutableStateOf("")
        private set
    var categories by mutableStateOf(emptyList<FavCategoryItem>())
        private set
    var currentFavCat by mutableIntStateOf(FavListUrlBuilder.FAV_CAT_ALL)
        private set
    var searchMode by mutableStateOf(false)
        private set
    var appliedKeyword by mutableStateOf("")
        private set
    var selection by mutableStateOf(emptySet<Long>(), neverEqualPolicy())
        private set
    var listMode by mutableIntStateOf(Settings.listMode)
        private set

    /** 一次性提示：UI 消费完调用 [consumeTip] 清空 */
    var tip by mutableStateOf<String?>(null)
        private set
    var tipLength by mutableIntStateOf(TIP_LENGTH_LONG)
        private set

    private var nextIndex: String? = null
    private var taskId = 0

    // For modify action
    private var enableModify = false
    private var modifyAdd = false
    private var modifyFavCat = 0
    private val modifyGiList = mutableListOf<GalleryInfo>()
    private var favSlot = -2

    init {
        urlBuilder.favCat = Settings.recentFavCat
        appliedKeyword = urlBuilder.keyword.orEmpty()
        currentFavCat = urlBuilder.favCat
        updateHeader()
        loadCurrent()
    }

    override fun onCleared() {
        // 页面离开返回栈：让在途请求自己结束，提示不要再弹到别的页面上
        tip = null
    }

    fun consumeTip(): String? = tip.also { tip = null }

    private fun showTip(tip: String, length: Int = TIP_LENGTH_LONG) {
        this.tip = tip
        tipLength = length
    }

    /** 回到前台时同步列表 / 网格模式（设置页可能刚改过） */
    fun syncListMode() {
        listMode = Settings.listMode
    }

    fun enterSearch() {
        searchMode = true
    }

    fun exitSearch() {
        searchMode = false
    }

    fun loadCurrent() {
        error = null
        loading = true
        loadingMore = false
        hasMore = false
        nextIndex = null
        if (urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
            loadLocal(urlBuilder.keyword)
        } else {
            loadCloud(null, false, false)
        }
    }

    fun refresh() {
        if (loading) return
        clearSelection()
        if (enableModify) {
            runModify()
        } else {
            loadCurrent()
        }
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        if (urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL) return
        val index = nextIndex ?: return
        loadingMore = true
        loadCloud(index, true, true)
    }

    private fun loadCloud(index: String?, isNext: Boolean, append: Boolean) {
        urlBuilder.setIndex(index, isNext)
        val url = urlBuilder.build()
        urlBuilder.jumpTo = null
        val tid = ++taskId
        if (!append) loading = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_FAVORITES)
            .setArgs(url)
            .setCallback(
                GetFavoritesListener(
                    taskId = tid,
                    localRequest = false,
                    keyword = urlBuilder.keyword,
                    gidArray = null,
                    slot = -2,
                    append = append,
                ),
            )
            .enqueue(viewModelScope)
    }

    private fun loadLocal(keyword: String?) {
        val tid = ++taskId
        viewModelScope.launchIO {
            val list = if (keyword.isNullOrEmpty()) {
                EhDB.allLocalFavorites
            } else {
                EhDB.searchLocalFavorites(keyword)
            }
            withUIContext {
                if (tid != taskId) return@withUIContext
                items = list
                hasMore = false
                loading = false
                loadingMore = false
                if (keyword.isNullOrEmpty()) {
                    favLocalCount = list.size
                    Settings.putFavLocalCount(favLocalCount)
                }
                updateHeader()
            }
        }
    }

    private fun applyPage(result: FavoritesParser.Result, append: Boolean) {
        favCatArray?.let { System.arraycopy(result.catArray, 0, it, 0, 10) }
        favCountArray = result.countArray
        favCountSum = (0..9).sumOf { result.countArray.getOrElse(it) { 0 } }
        Settings.putFavCloudCount(favCountSum)
        nextIndex = result.next
        hasMore = !result.next.isNullOrEmpty()
        items = if (append) items + result.galleryInfoList else result.galleryInfoList
        loading = false
        loadingMore = false
        error = null
        updateHeader()
    }

    private fun runModify() {
        enableModify = false
        val list = modifyGiList.toList()
        modifyGiList.clear()
        if (list.isEmpty()) {
            loadCurrent()
            return
        }
        val gidArray = list.map { it.gid }.toLongArray()
        val local = urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL
        loading = true
        if (modifyAdd) {
            val tokenArray = list.map { it.token }.toTypedArray()
            EhRequest()
                .setMethod(EhClient.METHOD_ADD_FAVORITES_RANGE)
                .setArgs(gidArray, tokenArray, modifyFavCat)
                .setCallback(
                    AddFavoritesListener(
                        taskId = ++taskId,
                        keyword = urlBuilder.keyword,
                        backup = list,
                        gidArray = gidArray,
                        slot = favSlot,
                    ),
                )
                .enqueue(viewModelScope)
        } else {
            urlBuilder.setIndex(null, false)
            val url = if (local) EhUrl.favoritesUrl else urlBuilder.build()
            urlBuilder.jumpTo = null
            EhRequest()
                .setMethod(EhClient.METHOD_MODIFY_FAVORITES)
                .setArgs(url, gidArray, modifyFavCat)
                .setCallback(
                    GetFavoritesListener(
                        taskId = ++taskId,
                        localRequest = local,
                        keyword = urlBuilder.keyword,
                        gidArray = gidArray,
                        slot = favSlot,
                        append = false,
                    ),
                )
                .enqueue(viewModelScope)
        }
    }

    fun toggleSelect(gid: Long) {
        selection = if (gid in selection) selection - gid else selection + gid
    }

    fun selectAll() {
        selection = items.map { it.gid }.toSet()
    }

    fun clearSelection() {
        if (selection.isNotEmpty()) selection = emptySet()
    }

    private fun selectedInfos(): List<GalleryInfo> = items.filter { it.gid in selection }

    fun downloadSelected(activity: MainActivity?) {
        activity ?: return
        val list = selectedInfos()
        if (list.isEmpty()) return
        clearSelection()
        CommonOperations.startDownload(activity, list, false)
    }

    fun deleteSelected() {
        val list = selectedInfos()
        if (list.isEmpty()) return
        clearSelection()
        favSlot = -2
        if (urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
            val gidArray = list.map { it.gid }.toLongArray()
            viewModelScope.launchIO {
                EhDB.removeLocalFavorites(gidArray)
                withUIContext {
                    updateHistoryFavSlot(gidArray, favSlot)
                    loadCurrent()
                }
            }
        } else {
            modifyGiList.clear()
            modifyGiList.addAll(list)
            enableModify = true
            modifyFavCat = -1
            modifyAdd = false
            refresh()
        }
    }

    fun moveSelected(index: Int) {
        val dstCat = if (index == 0) FavListUrlBuilder.FAV_CAT_LOCAL else index - 1
        val srcCat = urlBuilder.favCat
        if (srcCat == dstCat) return
        val list = selectedInfos()
        if (list.isEmpty()) return
        clearSelection()
        when {
            srcCat == FavListUrlBuilder.FAV_CAT_LOCAL -> {
                val gidArray = list.map { it.gid }.toLongArray()
                viewModelScope.launchIO {
                    EhDB.removeLocalFavorites(gidArray)
                    withUIContext {
                        modifyGiList.clear()
                        modifyGiList.addAll(list)
                        enableModify = true
                        modifyFavCat = dstCat
                        modifyAdd = true
                        favSlot = dstCat
                        refresh()
                    }
                }
            }
            dstCat == FavListUrlBuilder.FAV_CAT_LOCAL -> {
                viewModelScope.launchIO {
                    EhDB.putLocalFavorites(list)
                    withUIContext {
                        modifyGiList.clear()
                        modifyGiList.addAll(list)
                        enableModify = true
                        modifyFavCat = -1
                        modifyAdd = false
                        favSlot = -1
                        refresh()
                    }
                }
            }
            else -> {
                modifyGiList.clear()
                modifyGiList.addAll(list)
                enableModify = true
                modifyFavCat = dstCat
                modifyAdd = false
                favSlot = dstCat
                refresh()
            }
        }
    }

    fun applySearch(query: String) {
        if (searchMode) searchMode = false
        // 记进搜索历史，下次在搜索条里联想得到（与画廊列表页一致）
        if (query.isNotBlank()) {
            viewModelScope.launchIO { runCatching { searchDatabase.addQuery(query) } }
        }
        urlBuilder.keyword = query.takeIf { it.isNotBlank() }
        appliedKeyword = urlBuilder.keyword.orEmpty()
        updateHeader()
        refresh()
    }

    fun selectCategory(favCat: Int) {
        if (urlBuilder.favCat == favCat) return
        searchMode = false
        urlBuilder.keyword = null
        appliedKeyword = ""
        urlBuilder.favCat = favCat
        currentFavCat = favCat
        updateHeader()
        refresh()
    }

    fun setDefaultSlot(which: Int) {
        Settings.putDefaultFavSlot(which - 2)
        if (which == 0) {
            Settings.putNeverAddFavNotes(false)
        }
    }

    /** 与旧 `GalleryInfoContentHelper.goTo(Long, Boolean)` 等价：`seek` 传日期字符串 */
    fun goToDate(millis: Long) {
        urlBuilder.jumpTo = millis.toLocalDateTime().date.toString()
        loadCloud(nextIndex ?: "2", true, false)
    }

    fun goToLastPage() {
        urlBuilder.jumpTo = null
        loadCloud("1-0", false, false)
    }

    /** 日期选择器的合法区间：2007-03-21 到今天前一天 */
    fun goToDateRange(): LongRange {
        val start = LocalDate(2007, 3, 21).toEpochMillis()
        val end = Clock.System.todayIn(TimeZone.UTC).minus(1, DateTimeUnit.DAY).toEpochMillis()
        return start until end
    }

    fun goToDateDefault(): Long = goToDateRange().last

    private fun updateHeader() {
        val catName = categoryName(urlBuilder.favCat)
        val keyword = urlBuilder.keyword
        title = if (keyword.isNullOrEmpty()) {
            appContext.getString(R.string.favorites_title, catName)
        } else {
            appContext.getString(R.string.favorites_title_2, catName, keyword)
        }
        searchHint = appContext.getString(R.string.favorites_search_bar_hint, catName)
        categories = buildList {
            add(
                FavCategoryItem(
                    FavListUrlBuilder.FAV_CAT_LOCAL,
                    appContext.getString(R.string.local_favorites),
                    favLocalCount,
                ),
            )
            add(
                FavCategoryItem(
                    FavListUrlBuilder.FAV_CAT_ALL,
                    appContext.getString(R.string.cloud_favorites),
                    favCountSum,
                ),
            )
            favCatArray?.forEachIndexed { index, name ->
                add(FavCategoryItem(index, name, favCountArray?.getOrElse(index) { 0 } ?: 0))
            }
        }
        currentFavCat = urlBuilder.favCat
        Settings.putRecentFavCat(urlBuilder.favCat)
    }

    private fun categoryName(favCat: Int): String = when (favCat) {
        in 0..9 -> favCatArray?.getOrNull(favCat) ?: appContext.getString(R.string.cloud_favorites)
        FavListUrlBuilder.FAV_CAT_LOCAL -> appContext.getString(R.string.local_favorites)
        else -> appContext.getString(R.string.cloud_favorites)
    }

    private fun updateHistoryFavSlot(gidArray: LongArray?, slot: Int) {
        if (gidArray == null) return
        viewModelScope.launchIO {
            gidArray.forEach { gid -> EhDB.updateHistoryFavSlot(gid, slot) }
        }
    }

    private inner class AddFavoritesListener(
        private val taskId: Int,
        private val keyword: String?,
        private val backup: List<GalleryInfo>,
        private val gidArray: LongArray?,
        private val slot: Int,
    ) : EhClient.Callback<Void?> {
        override fun onSuccess(result: Void?) {
            if (taskId != this@FavoritesViewModel.taskId) return
            updateHistoryFavSlot(gidArray, slot)
            loadLocal(keyword)
        }

        override fun onFailure(e: Exception) {
            // 失败时把备份写回本地库，再重新读一遍
            viewModelScope.launchIO {
                EhDB.putLocalFavorites(backup)
                withUIContext { loadLocal(keyword) }
            }
        }

        override fun onCancel() {}
    }

    private inner class GetFavoritesListener(
        private val taskId: Int,
        // 当前显示的是本地收藏，但操作要打到云端
        private val localRequest: Boolean,
        private val keyword: String?,
        private val gidArray: LongArray?,
        private val slot: Int,
        private val append: Boolean,
    ) : EhClient.Callback<FavoritesParser.Result> {
        override fun onSuccess(result: FavoritesParser.Result) {
            if (taskId != this@FavoritesViewModel.taskId) return
            Settings.favCat = result.catArray
            Settings.favCount = result.countArray
            updateHistoryFavSlot(gidArray, slot)
            if (localRequest) {
                loadLocal(keyword)
            } else {
                applyPage(result, append)
            }
        }

        override fun onFailure(e: Exception) {
            if (taskId != this@FavoritesViewModel.taskId) return
            if (localRequest) {
                Log.e(TAG, "Error", e)
                loadLocal(keyword)
            } else {
                loading = false
                loadingMore = false
                val message = ExceptionUtils.getReadableString(e)
                if (items.isEmpty()) {
                    error = message
                } else {
                    showTip(message)
                }
            }
        }

        override fun onCancel() {
            loading = false
            loadingMore = false
        }
    }

    private companion object {
        const val TAG = "FavoritesViewModel"
    }
}
