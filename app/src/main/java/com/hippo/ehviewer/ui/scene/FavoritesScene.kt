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
package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
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
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.FavCategoryItem
import com.hippo.ehviewer.ui.compose.screen.FavoritesScreen
import com.hippo.util.ExceptionUtils
import com.hippo.util.getParcelableCompat
import com.hippo.util.launchIO
import com.hippo.util.toEpochMillis
import com.hippo.util.toLocalDateTime
import com.hippo.util.withUIContext
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * 收藏页。
 *
 * 结构调整：
 * - 分类列表住进场景内的 `ModalNavigationDrawer`（见 [FavoritesScreen]），不再占用宿主
 *   `DrawerLayout` 的右侧抽屉；宿主左侧导航抽屉仍由顶栏菜单按钮打开
 * - `ContentHelper` 的分页状态改成显式维护：`nextIndex` 承接服务端 `next`，
 *   `prev=1-0` 表示最后一页，`seek` 用日期字符串（与旧 `GalleryInfoContentHelper.goTo` 一致）
 * - 修改收藏（删除 / 移动 / 从本地加到云端）保留原来的「置 `enableModify` → 下一次加载先发修改请求」
 *   流程，因此列表刷新与修改结果仍然只走一次网络往返
 */
class FavoritesScene : EhComposeScene() {

    private var urlBuilder = FavListUrlBuilder()
    private var favCatArray: Array<String>? = Settings.favCat
    private var favCountArray: IntArray? = Settings.favCount
    private var favLocalCount = 0
    private var favCountSum = 0

    private var items by mutableStateOf(emptyList<GalleryInfo>(), neverEqualPolicy())
    private var loading by mutableStateOf(false)
    private var loadingMore by mutableStateOf(false)
    private var hasMore by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var nextIndex: String? = null
    private var taskId = 0

    private var title by mutableStateOf("")
    private var searchHint by mutableStateOf("")
    private var categories by mutableStateOf(emptyList<FavCategoryItem>())
    private var currentFavCat by mutableStateOf(FavListUrlBuilder.FAV_CAT_ALL)

    private var searchMode by mutableStateOf(false)
    private var appliedKeyword by mutableStateOf("")
    private var selection by mutableStateOf(emptySet<Long>(), neverEqualPolicy())
    private var listMode by mutableStateOf(Settings.listMode)

    // For modify action
    private var enableModify = false
    private var modifyAdd = false
    private var modifyFavCat = 0
    private var modifyGiList = mutableListOf<GalleryInfo>()
    private var favSlot = -2

    override fun getNavCheckedItem(): Int = R.id.nav_favourite

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favLocalCount = Settings.favLocalCount
        favCountSum = Settings.favCloudCount
        if (savedInstanceState == null) {
            urlBuilder.favCat = Settings.recentFavCat
        } else {
            savedInstanceState.getParcelableCompat<FavListUrlBuilder>(KEY_URL_BUILDER)
                ?.let { urlBuilder = it }
            searchMode = savedInstanceState.getBoolean(KEY_SEARCH_MODE)
            savedInstanceState.getIntArray(KEY_FAV_COUNT_ARRAY)?.let { favCountArray = it }
        }
        appliedKeyword = urlBuilder.keyword.orEmpty()
        currentFavCat = urlBuilder.favCat
        updateHeader()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_URL_BUILDER, urlBuilder)
        outState.putBoolean(KEY_SEARCH_MODE, searchMode)
        outState.putIntArray(KEY_FAV_COUNT_ARRAY, favCountArray)
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.Favorites> {
            LaunchedEffect(Unit) {
                if (items.isEmpty() && !loading && error == null) {
                    loadCurrent()
                }
            }
            FavoritesScreen(
                title = title,
                searchHint = searchHint,
                items = items,
                listMode = listMode,
                loading = loading,
                loadingMore = loadingMore,
                hasMore = hasMore,
                error = error,
                searchMode = searchMode,
                appliedKeyword = appliedKeyword,
                categories = categories,
                currentFavCat = currentFavCat,
                selection = selection,
                onOpenNavDrawer = { toggleDrawer(GravityCompat.START) },
                onRefresh = ::refresh,
                onLoadMore = ::loadMore,
                onOpenDetail = ::openDetail,
                onToggleSelect = ::toggleSelect,
                onSelectAll = ::selectAll,
                onClearSelection = ::clearSelection,
                onDownloadSelected = ::downloadSelected,
                onDeleteSelected = ::deleteSelected,
                onMoveSelected = ::moveSelected,
                onEnterSearch = { searchMode = true },
                onExitSearch = { searchMode = false },
                onApplySearch = ::applySearch,
                onSelectCategory = ::selectCategory,
                onSetDefaultSlot = ::setDefaultSlot,
                onGoToDate = ::showGoToDialog,
                onGoToLastPage = ::goToLastPage,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 与旧实现一致：每次回到页面同步一次列表/网格模式（设置页可能刚改过）
        listMode = Settings.listMode
    }

    override fun onBackPressed() {
        when {
            selection.isNotEmpty() -> clearSelection()
            searchMode -> searchMode = false
            else -> finish()
        }
    }

    private fun loadCurrent() {
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

    private fun refresh() {
        if (loading) return
        clearSelection()
        if (enableModify) {
            runModify()
        } else {
            loadCurrent()
        }
    }

    private fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        if (urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL) return
        val index = nextIndex ?: return
        loadingMore = true
        loadCloud(index, true, true)
    }

    private fun loadCloud(index: String?, isNext: Boolean, append: Boolean) {
        val ctx = context ?: return
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
                    context = ctx,
                    taskId = tid,
                    localRequest = false,
                    keyword = urlBuilder.keyword,
                    gidArray = null,
                    slot = -2,
                    append = append,
                ),
            )
            .enqueue(this)
    }

    private fun loadLocal(keyword: String?) {
        val tid = ++taskId
        lifecycleScope.launchIO {
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
        val ctx = context ?: return
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
                        context = ctx,
                        taskId = ++taskId,
                        keyword = urlBuilder.keyword,
                        backup = list,
                        gidArray = gidArray,
                        slot = favSlot,
                    ),
                )
                .enqueue(this)
        } else {
            urlBuilder.setIndex(null, false)
            val url = if (local) EhUrl.favoritesUrl else urlBuilder.build()
            urlBuilder.jumpTo = null
            EhRequest()
                .setMethod(EhClient.METHOD_MODIFY_FAVORITES)
                .setArgs(url, gidArray, modifyFavCat)
                .setCallback(
                    GetFavoritesListener(
                        context = ctx,
                        taskId = ++taskId,
                        localRequest = local,
                        keyword = urlBuilder.keyword,
                        gidArray = gidArray,
                        slot = favSlot,
                        append = false,
                    ),
                )
                .enqueue(this)
        }
    }

    private fun openDetail(gi: GalleryInfo) {
        ehNavigator().push(EhRoute.GalleryDetail(gid = gi.gid, token = gi.token))
    }

    private fun toggleSelect(gid: Long) {
        selection = if (gid in selection) selection - gid else selection + gid
    }

    private fun selectAll() {
        selection = items.map { it.gid }.toSet()
    }

    private fun clearSelection() {
        if (selection.isNotEmpty()) selection = emptySet()
    }

    private fun selectedInfos(): List<GalleryInfo> = items.filter { it.gid in selection }

    private fun downloadSelected() {
        val activity = mainActivity ?: return
        val list = selectedInfos()
        if (list.isEmpty()) return
        clearSelection()
        CommonOperations.startDownload(activity, list, false)
    }

    private fun deleteSelected() {
        val list = selectedInfos()
        if (list.isEmpty()) return
        clearSelection()
        favSlot = -2
        if (urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
            val gidArray = list.map { it.gid }.toLongArray()
            lifecycleScope.launchIO {
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

    private fun moveSelected(index: Int) {
        val dstCat = if (index == 0) FavListUrlBuilder.FAV_CAT_LOCAL else index - 1
        val srcCat = urlBuilder.favCat
        if (srcCat == dstCat) return
        val list = selectedInfos()
        if (list.isEmpty()) return
        clearSelection()
        when {
            srcCat == FavListUrlBuilder.FAV_CAT_LOCAL -> {
                val gidArray = list.map { it.gid }.toLongArray()
                lifecycleScope.launchIO {
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
                lifecycleScope.launchIO {
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

    private fun applySearch(query: String) {
        if (searchMode) searchMode = false
        urlBuilder.keyword = query.takeIf { it.isNotBlank() }
        appliedKeyword = urlBuilder.keyword.orEmpty()
        updateHeader()
        refresh()
    }

    private fun selectCategory(favCat: Int) {
        if (urlBuilder.favCat == favCat) return
        searchMode = false
        urlBuilder.keyword = null
        appliedKeyword = ""
        urlBuilder.favCat = favCat
        currentFavCat = favCat
        updateHeader()
        refresh()
    }

    private fun setDefaultSlot(which: Int) {
        Settings.putDefaultFavSlot(which - 2)
        if (which == 0) {
            Settings.putNeverAddFavNotes(false)
        }
    }

    private fun showGoToDialog() {
        val initial = LocalDate(2007, 3, 21)
        val yesterday = Clock.System.todayIn(TimeZone.UTC).minus(1, DateTimeUnit.DAY)
        val initialMillis = initial.toEpochMillis()
        val yesterdayMillis = yesterday.toEpochMillis()
        val constraints = CalendarConstraints.Builder()
            .setStart(initialMillis)
            .setEnd(yesterdayMillis)
            .setValidator(
                CompositeDateValidator.allOf(
                    listOf(
                        DateValidatorPointForward.from(initialMillis),
                        DateValidatorPointBackward.before(yesterdayMillis),
                    ),
                ),
            )
            .build()
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setCalendarConstraints(constraints)
            .setTitleText(R.string.go_to)
            .setSelection(yesterdayMillis)
            .build()
        datePicker.show(requireActivity().supportFragmentManager, "date-picker")
        datePicker.addOnPositiveButtonClickListener { millis ->
            if (millis != null) goToDate(millis)
        }
    }

    /** 与旧 `GalleryInfoContentHelper.goTo(Long, Boolean)` 等价：`seek` 传日期字符串 */
    private fun goToDate(millis: Long) {
        urlBuilder.jumpTo = millis.toLocalDateTime().date.toString()
        loadCloud(nextIndex ?: "2", true, false)
    }

    private fun goToLastPage() {
        urlBuilder.jumpTo = null
        loadCloud("1-0", false, false)
    }

    private fun updateHeader() {
        val catName = categoryName(urlBuilder.favCat)
        val keyword = urlBuilder.keyword
        title = if (keyword.isNullOrEmpty()) {
            getString(R.string.favorites_title, catName)
        } else {
            getString(R.string.favorites_title_2, catName, keyword)
        }
        searchHint = getString(R.string.favorites_search_bar_hint, catName)
        categories = buildList {
            add(
                FavCategoryItem(
                    FavListUrlBuilder.FAV_CAT_LOCAL,
                    getString(R.string.local_favorites),
                    favLocalCount,
                ),
            )
            add(
                FavCategoryItem(
                    FavListUrlBuilder.FAV_CAT_ALL,
                    getString(R.string.cloud_favorites),
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
        in 0..9 -> favCatArray?.getOrNull(favCat) ?: getString(R.string.cloud_favorites)
        FavListUrlBuilder.FAV_CAT_LOCAL -> getString(R.string.local_favorites)
        else -> getString(R.string.cloud_favorites)
    }

    private fun updateHistoryFavSlot(gidArray: LongArray?, slot: Int) {
        if (gidArray == null) return
        lifecycleScope.launchIO {
            gidArray.forEach { gid -> EhDB.updateHistoryFavSlot(gid, slot) }
        }
    }

    private inner class AddFavoritesListener(
        context: Context,
        private val taskId: Int,
        private val keyword: String?,
        private val backup: List<GalleryInfo>,
        private val gidArray: LongArray?,
        private val slot: Int,
    ) : EhCallback<FavoritesScene?, Void?>(context) {
        override fun onSuccess(result: Void?) {
            if (taskId != this@FavoritesScene.taskId) return
            updateHistoryFavSlot(gidArray, slot)
            loadLocal(keyword)
        }

        override fun onFailure(e: Exception) {
            // 失败时把备份写回本地库，再重新读一遍
            lifecycleScope.launchIO {
                EhDB.putLocalFavorites(backup)
                withUIContext { loadLocal(keyword) }
            }
        }

        override fun onCancel() {}
    }

    private inner class GetFavoritesListener(
        context: Context,
        private val taskId: Int,
        // 当前显示的是本地收藏，但操作要打到云端
        private val localRequest: Boolean,
        private val keyword: String?,
        private val gidArray: LongArray?,
        private val slot: Int,
        private val append: Boolean,
    ) : EhCallback<FavoritesScene?, FavoritesParser.Result>(context) {
        override fun onSuccess(result: FavoritesParser.Result) {
            if (taskId != this@FavoritesScene.taskId) return
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
            if (taskId != this@FavoritesScene.taskId) return
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
                    showTip(message, LENGTH_LONG)
                }
            }
        }

        override fun onCancel() {
            loading = false
            loadingMore = false
        }
    }

    companion object {
        private const val TAG = "FavoritesScene"
        private const val KEY_URL_BUILDER = "url_builder"
        private const val KEY_SEARCH_MODE = "search_mode"
        private const val KEY_FAV_COUNT_ARRAY = "fav_count_array"
    }
}
