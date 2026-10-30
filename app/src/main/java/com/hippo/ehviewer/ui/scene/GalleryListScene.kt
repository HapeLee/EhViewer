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
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.hippo.ehviewer.EhApplication.Companion.favouriteStatusRouter
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_SUBSCRIPTION
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_TOPLIST
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_WHATS_HOT
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.GalleryListParser
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.component.GalleryItemLayout
import com.hippo.ehviewer.ui.compose.component.SearchCriteria
import com.hippo.ehviewer.ui.compose.component.SearchPanelState
import com.hippo.ehviewer.ui.compose.component.SearchPanelTab
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.nav.galleryQueryOf
import com.hippo.ehviewer.ui.compose.screen.GalleryListScreen
import com.hippo.ehviewer.ui.compose.screen.GalleryListUiMode
import com.hippo.ehviewer.ui.compose.screen.GalleryListUiState
import com.hippo.ehviewer.ui.compose.screen.TopListRange
import com.hippo.ehviewer.ui.compose.screen.UrlSuggestionUi
import com.hippo.ehviewer.ui.compose.screen.parseUrlSuggestion
import com.hippo.unifile.UniFile
import com.hippo.unifile.sha1
import com.hippo.util.ExceptionUtils
import com.hippo.util.getParcelableCompat
import com.hippo.util.launchIO
import com.hippo.util.toEpochMillis
import com.hippo.util.toLocalDateTime
import com.hippo.util.withUIContext
import com.hippo.yorozuya.MathUtils.hammingWeight
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * 画廊列表页（首页 / 订阅 / 热门 / 榜单 / 任意搜索结果）。
 *
 * 结构调整：
 * - 界面整体搬到 Compose（[GalleryListScreen]），本类只保留状态、请求和宿主交互；
 *   顶栏三态、右侧「快速搜索 / 榜单范围」抽屉、长按操作菜单都在屏内实现
 * - 分页改成显式维护：非榜单用 `prev` / `next` 游标，榜单用页码（`seek`/`p=`），
 *   与旧 `GalleryInfoContentHelper` 的 `goTo(index, isNext)` / `goTo(page)` 语义一致
 * - 收藏槽位与下载状态不再刷新整个 Adapter，而是就地改 `GalleryInfo` 并自增 [version]，
 *   由 `uiState()` 把版本号带进快照触发重组
 */
class GalleryListScene : EhComposeScene() {

    private var urlBuilder = ListUrlBuilder()
    private var isTopList = false
    private var isPopular = false

    private var items by mutableStateOf(emptyList<GalleryInfo>(), neverEqualPolicy())
    private var version by mutableStateOf(0)
    private var loading by mutableStateOf(false)
    private var loadingMore by mutableStateOf(false)
    private var hasMore by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var emptyText by mutableStateOf<String?>(null)
    private var uiMode by mutableStateOf(GalleryListUiMode.NORMAL)
    private var title by mutableStateOf("")
    private var searchHint by mutableStateOf("")
    private var keywordQuery by mutableStateOf("")
    private var layoutMode by mutableStateOf(Settings.listMode)
    private var quickSearches by mutableStateOf(emptyList<QuickSearch>())
    private var downloadLabels by mutableStateOf(listOf<String>())
    private var topListPages by mutableStateOf(0)
    private var topListPage by mutableStateOf(1)
    private var selectedTopListRange by mutableStateOf<String?>(null)
    private var searchPanel = SearchPanelState()

    private var nextIndex: String? = null
    private var nextTopListPage = 0
    private var taskId = 0
    private var pressBackTime = 0L

    private val downloadInfoListener: DownloadInfoListener = object : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
            bumpVersion()
        }
        override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>) = bumpVersion()
        override fun onUpdateAll() = bumpVersion()
        override fun onReload() = bumpVersion()
        override fun onChange() = bumpVersion()
        override fun onRenameLabel(from: String, to: String) = bumpVersion()
        override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) = bumpVersion()
        override fun onUpdateLabels() = bumpVersion()
    }

    private val favouriteStatusRouterListener = FavouriteStatusRouter.Listener { gid, slot ->
        val index = items.indexOfFirst { it.gid == gid }
        if (index >= 0) {
            items[index].favoriteSlot = slot
            bumpVersion()
        }
    }

    override fun getNavCheckedItem(): Int = when (urlBuilder.mode) {
        ListUrlBuilder.MODE_NORMAL -> R.id.nav_homepage
        MODE_SUBSCRIPTION -> R.id.nav_subscription
        MODE_WHATS_HOT -> R.id.nav_whats_hot
        MODE_TOPLIST -> R.id.nav_toplist
        else -> 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.addDownloadInfoListener(downloadInfoListener)
        favouriteStatusRouter.addListener(favouriteStatusRouterListener)
        if (savedInstanceState == null) {
            handleArgs(arguments)
        } else {
            savedInstanceState.getParcelableCompat<ListUrlBuilder>(KEY_LIST_URL_BUILDER)?.let {
                urlBuilder = it
            }
            uiMode = savedInstanceState.getInt(KEY_UI_MODE, GalleryListUiMode.NORMAL)
            topListPage = savedInstanceState.getInt(KEY_TOP_LIST_PAGE, 1)
            topListPages = savedInstanceState.getInt(KEY_TOP_LIST_PAGES, 0)
        }
        isTopList = urlBuilder.mode == MODE_TOPLIST
        isPopular = urlBuilder.mode == MODE_WHATS_HOT
        keywordQuery = urlBuilder.keyword.orEmpty()
        selectedTopListRange = urlBuilder.keyword
        searchPanel = newSearchPanel()
        updateHeader()
        refreshDownloadLabels()
        loadQuickSearches()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_LIST_URL_BUILDER, urlBuilder)
        outState.putInt(KEY_UI_MODE, uiMode)
        outState.putInt(KEY_TOP_LIST_PAGE, topListPage)
        outState.putInt(KEY_TOP_LIST_PAGES, topListPages)
    }

    override fun onNewArguments(args: Bundle) {
        handleArgs(args)
        isTopList = urlBuilder.mode == MODE_TOPLIST
        isPopular = urlBuilder.mode == MODE_WHATS_HOT
        keywordQuery = urlBuilder.keyword.orEmpty()
        selectedTopListRange = urlBuilder.keyword
        searchPanel = newSearchPanel()
        uiMode = GalleryListUiMode.NORMAL
        updateHeader()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 与旧实现一致：回到页面时同步一次列表/网格模式（设置页可能刚改过）
        layoutMode = Settings.listMode
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadManager.removeDownloadInfoListener(downloadInfoListener)
        favouriteStatusRouter.removeListener(favouriteStatusRouterListener)
    }

    override fun onBackPressed() {
        when (uiMode) {
            GalleryListUiMode.SEARCH -> uiMode = GalleryListUiMode.NORMAL
            GalleryListUiMode.SIMPLE_SEARCH -> uiMode = GalleryListUiMode.NORMAL
            else -> {
                // 与旧实现一致：只有根页面才需要双击退出，非根页面直接返回上一级
                if (stackIndex != 0) {
                    finish()
                    return
                }
                val time = System.currentTimeMillis()
                if (time - pressBackTime > BACK_PRESSED_INTERVAL) {
                    pressBackTime = time
                    showTip(R.string.press_twice_exit, LENGTH_SHORT)
                } else {
                    finish()
                }
            }
        }
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.GalleryList> {
            LaunchedEffect(Unit) {
                if (items.isEmpty() && !loading && error == null) {
                    refresh()
                }
            }
            GalleryListScreen(
                state = uiState(),
                searchPanel = searchPanel,
                query = keywordQuery,
                onQueryChange = { keywordQuery = it },
                onOpenNavDrawer = { toggleDrawer(GravityCompat.START) },
                onEnterSimpleSearch = {
                    keywordQuery = urlBuilder.keyword.orEmpty()
                    uiMode = GalleryListUiMode.SIMPLE_SEARCH
                },
                onEnterSearch = {
                    searchPanel = newSearchPanel()
                    keywordQuery = urlBuilder.keyword.orEmpty()
                    uiMode = GalleryListUiMode.SEARCH
                },
                onExitSearch = { uiMode = GalleryListUiMode.NORMAL },
                onApplySearch = ::applySearch,
                onSuggestionClick = ::openUrlSuggestion,
                onPickImage = ::pickImage,
                onClearImage = { searchPanel.imageUri = null },
                onRefresh = ::refresh,
                onLoadMore = ::loadMore,
                onOpenDetail = ::openDetail,
                onRead = ::readGallery,
                onDownload = ::downloadGallery,
                onDeleteDownload = { DownloadManager.deleteDownload(it.gid) },
                onToggleFavorite = ::toggleFavorite,
                onMoveDownloadLabel = ::moveDownloadLabel,
                onToggleLayoutMode = ::toggleLayoutMode,
                onGoToDate = ::showGoToDateDialog,
                onGoToLastPage = { requestCursor("1-0", null, false, false) },
                onGoToPage = { page -> requestTopList(page, false) },
                onGoToGid = ::goToGid,
                onSaveQuickSearch = ::saveQuickSearch,
                onSelectQuickSearch = ::selectQuickSearch,
                onDeleteQuickSearch = ::deleteQuickSearch,
                onMoveQuickSearch = ::moveQuickSearch,
                onSelectTopListRange = ::selectTopListRange,
                downloadLabelOptions = downloadLabels,
                pageCount = topListPages,
            )
        }
    }

    private fun uiState(): GalleryListUiState = GalleryListUiState(
        title = title,
        searchHint = searchHint,
        items = items,
        layoutMode = layoutMode,
        loading = loading,
        loadingMore = loadingMore,
        hasMore = hasMore,
        error = error,
        emptyText = emptyText,
        mode = uiMode,
        isTopList = isTopList,
        isPopular = isPopular,
        topListRanges = topListRanges(),
        selectedTopListRange = selectedTopListRange,
        topListPages = topListPages,
        topListPage = topListPage,
        quickSearches = quickSearches,
        keyword = keywordQuery,
        suggestion = parseUrlSuggestion(keywordQuery),
        version = version,
    )

    private fun openUrlSuggestion() {
        when (val suggestion = parseUrlSuggestion(keywordQuery)) {
            is UrlSuggestionUi.Detail -> {
                ehNavigator().push(
                    EhRoute.GalleryDetail(gid = suggestion.gid, token = suggestion.token),
                )
            }
            is UrlSuggestionUi.Page -> {
                ehNavigator().push(
                    EhRoute.Progress(
                        gid = suggestion.gid,
                        pToken = suggestion.pToken,
                        page = suggestion.page,
                    ),
                )
            }
            null -> return
        }
        uiMode = GalleryListUiMode.NORMAL
    }

    private fun bumpVersion() {
        version++
    }

    private fun handleArgs(args: Bundle?) {
        args ?: return
        urlBuilder = when (args.getString(KEY_ACTION)) {
            ACTION_HOMEPAGE -> ListUrlBuilder()
            ACTION_SUBSCRIPTION -> ListUrlBuilder(MODE_SUBSCRIPTION)
            ACTION_WHATS_HOT -> ListUrlBuilder(MODE_WHATS_HOT)
            ACTION_TOP_LIST -> ListUrlBuilder(MODE_TOPLIST).apply { keyword = Settings.defaultTopList }
            ACTION_LIST_URL_BUILDER -> args.getParcelableCompat<ListUrlBuilder>(KEY_LIST_URL_BUILDER)
                ?.copy() ?: ListUrlBuilder()
            else -> throw IllegalStateException(
                "Wrong KEY_ACTION:${args.getString(KEY_ACTION)} when handle args!",
            )
        }
    }

    private fun newSearchPanel(): SearchPanelState {
        val mode = urlBuilder.mode
        val isImageSearch = mode == ListUrlBuilder.MODE_IMAGE_SEARCH
        return SearchPanelState(
            tab = if (isImageSearch) SearchPanelTab.IMAGE else SearchPanelTab.NORMAL,
            searchMode = if (isImageSearch || mode == MODE_WHATS_HOT || mode == MODE_TOPLIST) {
                ListUrlBuilder.MODE_NORMAL
            } else {
                mode
            },
            category = urlBuilder.category,
            advanceEnabled = urlBuilder.advanceSearch != -1,
            advanceSearch = urlBuilder.advanceSearch.coerceAtLeast(0),
            minRating = urlBuilder.minRating,
            pageFrom = urlBuilder.pageFrom.takeIf { it != -1 }?.toString().orEmpty(),
            pageTo = urlBuilder.pageTo.takeIf { it != -1 }?.toString().orEmpty(),
            imageUri = null,
        )
    }

    private fun topListRanges(): List<TopListRange> = listOf(
        TopListRange("15", getString(R.string.toplist_yesterday)),
        TopListRange("13", getString(R.string.toplist_pastmonth)),
        TopListRange("12", getString(R.string.toplist_pastyear)),
        TopListRange("11", getString(R.string.toplist_alltime)),
    )

    /** 与旧实现一致：`tag:` 搜索在输入框里显示成 `tag$` 形式 */
    private fun wrapTagKeyword(keyword: String): String = if (keyword.endsWith(':')) {
        keyword
    } else if (keyword.contains(" ")) {
        val tag = keyword.substringAfter(':')
        val prefix = keyword.dropLast(tag.length)
        "$prefix\"$tag$\""
    } else {
        "$keyword$"
    }

    private fun updateHeader() {
        val keyword = urlBuilder.keyword
        if (!isTopList && urlBuilder.mode == ListUrlBuilder.MODE_TAG && !keyword.isNullOrEmpty()) {
            keywordQuery = wrapTagKeyword(keyword)
        }
        title = getSuitableTitleForUrlBuilder(resources, urlBuilder, false)
            ?: urlBuilder.keyword.orEmpty()
        searchHint = getString(
            if (EhUtils.isExHentai) {
                R.string.gallery_list_search_bar_hint_exhentai
            } else {
                R.string.gallery_list_search_bar_hint_e_hentai
            },
        )
    }

    // region 分页与请求

    private fun refresh() {
        error = null
        emptyText = null
        if (isTopList) {
            requestTopList(1, false)
        } else {
            requestCursor(null, null, true, false)
        }
    }

    private fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        if (isTopList) {
            requestTopList(nextTopListPage, true)
        } else {
            requestCursor(nextIndex, null, true, true)
        }
    }

    /**
     * 游标翻页。
     *
     * @param index `prev` / `next` 游标，首页传 null
     * @param seek 日期字符串（对应 `seek=`），只与「跳到日期」和快速搜索进度一起用
     * @param isNext index 走 `next` 还是 `prev`
     */
    private fun requestCursor(index: String?, seek: String?, isNext: Boolean, append: Boolean) {
        val ctx = context ?: return
        if (!isTopList) {
            urlBuilder.setJumpTo(seek)
            urlBuilder.setIndex(index, isNext)
        }
        val url = urlBuilder.build()
        urlBuilder.setJumpTo(null)
        urlBuilder.setIndex(null, true)
        val tid = ++taskId
        if (!append) {
            loading = true
        } else {
            loadingMore = true
        }
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
            .setArgs(url)
            .setCallback(GalleryListListener(ctx, tid, append, isTopListRequest = false, page = 0))
            .enqueue(this)
    }

    private fun requestTopList(page: Int, append: Boolean) {
        val ctx = context ?: return
        urlBuilder.setJumpTo(page.toString())
        val url = urlBuilder.build()
        urlBuilder.setJumpTo(null)
        val tid = ++taskId
        if (!append) {
            loading = true
        } else {
            loadingMore = true
        }
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
            .setArgs(url)
            .setCallback(GalleryListListener(ctx, tid, append, isTopListRequest = true, page = page))
            .enqueue(this)
    }

    private fun applyPage(result: GalleryListParser.Result, append: Boolean, topListPageRequested: Int) {
        if (topListPageRequested > 0) {
            topListPages = result.pages
            topListPage = topListPageRequested
            nextTopListPage = result.nextPage
            hasMore = result.nextPage in 1..result.pages
        } else {
            nextIndex = result.next
            hasMore = !result.next.isNullOrEmpty()
        }
        items = if (append) items + result.galleryInfoList else result.galleryInfoList
        emptyText = if (urlBuilder.mode == MODE_SUBSCRIPTION && result.noWatchedTags) {
            getString(R.string.gallery_list_empty_hit_subscription)
        } else {
            null
        }
        loading = false
        loadingMore = false
        error = null
        bumpVersion()
    }

    private fun onLoadFailure(e: Exception, append: Boolean) {
        loading = false
        loadingMore = false
        val message = ExceptionUtils.getReadableString(e)
        if (append || items.isNotEmpty()) {
            showTip(message, LENGTH_LONG)
        } else {
            error = message
        }
    }

    // endregion

    // region 搜索

    private fun applySearch(query: String) {
        if (uiMode == GalleryListUiMode.SEARCH) {
            when (val criteria = searchPanel.buildCriteria(query)) {
                is SearchCriteria.Failure -> {
                    showTip(getString(criteria.messageRes), LENGTH_LONG)
                    return
                }
                is SearchCriteria.Success -> {
                    if (criteria.mode == ListUrlBuilder.MODE_IMAGE_SEARCH) {
                        val uri = searchPanel.imageUri
                        if (uri == null) {
                            showTip(getString(R.string.select_image_first), LENGTH_LONG)
                            return
                        }
                        val ctx = context ?: return
                        lifecycleScope.launchIO {
                            val hash = UniFile.fromUri(ctx, uri)?.sha1()
                            withUIContext {
                                if (hash == null) {
                                    showTip(getString(R.string.select_image_first), LENGTH_LONG)
                                } else {
                                    urlBuilder.reset()
                                    urlBuilder.mode = ListUrlBuilder.MODE_IMAGE_SEARCH
                                    urlBuilder.hash = hash
                                    finishSearch()
                                }
                            }
                        }
                        return
                    }
                    urlBuilder.reset()
                    urlBuilder.mode = criteria.mode
                    urlBuilder.category = criteria.category
                    urlBuilder.keyword = criteria.keyword
                    urlBuilder.advanceSearch = criteria.advanceSearch
                    urlBuilder.minRating = criteria.minRating
                    urlBuilder.pageFrom = criteria.pageFrom
                    urlBuilder.pageTo = criteria.pageTo
                }
            }
        } else {
            // 简单搜索：与旧实现一致，只保留「订阅搜索」模式，其余条件全部重置
            val newMode =
                if (urlBuilder.mode == MODE_SUBSCRIPTION) MODE_SUBSCRIPTION else ListUrlBuilder.MODE_NORMAL
            urlBuilder.reset()
            urlBuilder.mode = newMode
            urlBuilder.keyword = query
        }
        finishSearch()
    }

    private fun finishSearch() {
        isTopList = urlBuilder.mode == MODE_TOPLIST
        isPopular = urlBuilder.mode == MODE_WHATS_HOT
        selectedTopListRange = urlBuilder.keyword
        uiMode = GalleryListUiMode.NORMAL
        updateHeader()
        setNavCheckedItem(getNavCheckedItem())
        refresh()
    }

    private fun pickImage() {
        pickImageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(IMAGE_ONLY))
    }

    private val pickImageLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) {
            if (it != null) {
                searchPanel.imageUri = it
            }
        }

    // endregion

    // region 条目操作

    private fun openDetail(gi: GalleryInfo) {
        ehNavigator().push(EhRoute.GalleryDetail(gid = gi.gid, token = gi.token))
    }

    private fun readGallery(gi: GalleryInfo) {
        val activity = mainActivity ?: return
        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_EH
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, gi)
        startActivity(intent)
    }

    private fun downloadGallery(gi: GalleryInfo) {
        val activity = mainActivity ?: return
        CommonOperations.startDownload(activity, gi, false)
    }

    private fun toggleFavorite(gi: GalleryInfo) {
        val activity = mainActivity ?: return
        val listener = FavoriteChangeListener(context ?: return, gi.gid)
        if (gi.favoriteSlot != -2) {
            CommonOperations.removeFromFavorites(activity, gi, listener)
        } else {
            CommonOperations.addToFavorites(activity, gi, listener, false)
        }
    }

    private fun moveDownloadLabel(gi: GalleryInfo, index: Int) {
        val info = DownloadManager.getDownloadInfo(gi.gid) ?: return
        val label = if (index <= 0) null else downloadLabels.getOrNull(index)
        DownloadManager.changeLabel(listOf(info), label)
    }

    private fun refreshDownloadLabels() {
        val labels = mutableListOf(getString(R.string.default_download_label_name))
        DownloadManager.labelList.forEach { it.label?.let(labels::add) }
        downloadLabels = labels
    }

    private fun toggleLayoutMode() {
        val next = if (layoutMode == GalleryItemLayout.GRID) {
            GalleryItemLayout.LIST
        } else {
            GalleryItemLayout.GRID
        }
        Settings.putListMode(next)
        layoutMode = next
    }

    // endregion

    // region 跳转

    private fun goToGid(gid: Long) {
        requestCursor((gid + 1).toString(), null, gid != 1L, false)
    }

    private fun showGoToDateDialog() {
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
        datePicker.addOnPositiveButtonClickListener { millis ->
            requestCursor(null, millis.toLocalDateTime().date.toString(), true, false)
        }
        datePicker.show(requireActivity().supportFragmentManager, "date-picker")
    }

    private fun selectTopListRange(id: String) {
        Settings.putDefaultTopList(id)
        urlBuilder.keyword = id
        selectedTopListRange = id
        updateHeader()
        refresh()
    }

    // endregion

    // region 快速搜索

    private fun loadQuickSearches() {
        lifecycleScope.launchIO {
            val list = EhDB.allQuickSearch
            withUIContext { quickSearches = list }
        }
    }

    private fun selectQuickSearch(quickSearch: QuickSearch) {
        urlBuilder.set(quickSearch)
        isTopList = false
        isPopular = false
        val name = quickSearch.name.orEmpty()
        val anchor = name.lastIndexOf('@')
        val seek = if (anchor != -1) name.substring(anchor + 1) else null
        keywordQuery = urlBuilder.keyword.orEmpty()
        uiMode = GalleryListUiMode.NORMAL
        updateHeader()
        setNavCheckedItem(getNavCheckedItem())
        requestCursor(null, seek, true, false)
    }

    private fun saveQuickSearch(name: String, saveProgress: Boolean, anchorGid: Long?) {
        if (urlBuilder.mode == ListUrlBuilder.MODE_IMAGE_SEARCH) {
            showTip(R.string.image_search_not_quick_search, LENGTH_LONG)
            return
        }
        if (name.isEmpty()) {
            showTip(R.string.name_is_empty, LENGTH_LONG)
            return
        }
        val anchor = anchorGid?.let { "@" + (it + 1) }
        // 与旧实现一致：条件完全相同的快速搜索且进度锚点也一致时才提示重复
        for (existing in quickSearches) {
            if (!urlBuilder.equalsQuickSearch(existing)) continue
            val existingName = existing.name.orEmpty()
            val at = existingName.lastIndexOf('@')
            if (at != -1 && anchor != null && existingName.substring(at) == anchor) {
                showTip(getString(R.string.duplicate_quick_search, existingName), LENGTH_LONG)
                return
            }
        }
        Settings.putQSSaveProgress(saveProgress)
        val text = if (saveProgress && anchor != null) name + anchor else name
        if (quickSearches.any { it.name == text }) {
            showTip(R.string.duplicate_name, LENGTH_LONG)
            return
        }
        val quickSearch = urlBuilder.toQuickSearch()
        quickSearch.name = text
        lifecycleScope.launchIO {
            EhDB.insertQuickSearch(quickSearch)
            val list = EhDB.allQuickSearch
            withUIContext { quickSearches = list }
        }
    }

    private fun deleteQuickSearch(quickSearch: QuickSearch) {
        quickSearches = quickSearches.filter { it.id != quickSearch.id }
        lifecycleScope.launchIO {
            EhDB.deleteQuickSearch(quickSearch)
        }
    }

    private fun moveQuickSearch(from: Int, to: Int) {
        if (from == to) return
        val list = quickSearches.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        quickSearches = list
        lifecycleScope.launchIO {
            EhDB.moveQuickSearch(from, to)
        }
    }

    // endregion

    private inner class GalleryListListener(
        context: Context,
        private val taskId: Int,
        private val append: Boolean,
        private val isTopListRequest: Boolean,
        private val page: Int,
    ) : EhCallback<GalleryListScene, GalleryListParser.Result>(context) {
        override fun onSuccess(result: GalleryListParser.Result) {
            if (taskId != this@GalleryListScene.taskId) return
            applyPage(result, append, if (isTopListRequest) page else 0)
        }

        override fun onFailure(e: Exception) {
            if (taskId != this@GalleryListScene.taskId) return
            onLoadFailure(e, append)
        }

        override fun onCancel() {
            loading = false
            loadingMore = false
        }
    }

    private inner class FavoriteChangeListener(
        context: Context,
        private val gid: Long,
    ) : EhCallback<GalleryListScene, Unit>(context) {
        override fun onSuccess(result: Unit) {
            // 真正的槽位由 FavouriteStatusRouter 广播回来，这里只负责重新读一次状态
            if (items.none { it.gid == gid }) return
            bumpVersion()
        }

        override fun onFailure(e: Exception) {
            Log.e(TAG, "Change favourite state failed", e)
        }

        override fun onCancel() {}
    }

    companion object {
        private const val TAG = "GalleryListScene"
        private const val BACK_PRESSED_INTERVAL = 2000L

        const val KEY_ACTION = "action"
        const val ACTION_HOMEPAGE = "action_homepage"
        const val ACTION_SUBSCRIPTION = "action_subscription"
        const val ACTION_WHATS_HOT = "action_whats_hot"
        const val ACTION_TOP_LIST = "action_top_list"
        const val ACTION_LIST_URL_BUILDER = "action_list_url_builder"
        const val KEY_LIST_URL_BUILDER = "list_url_builder"
        private const val KEY_UI_MODE = "ui_mode"
        private const val KEY_TOP_LIST_PAGE = "top_list_page"
        private const val KEY_TOP_LIST_PAGES = "top_list_pages"

        private val IMAGE_ONLY =
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly

        private fun getSuitableTitleForUrlBuilder(
            resources: android.content.res.Resources,
            urlBuilder: ListUrlBuilder,
            appName: Boolean,
        ): String? {
            val keyword = urlBuilder.keyword
            val category = urlBuilder.category
            val noCondition = EhUtils.NONE == category &&
                TextUtils.isEmpty(keyword) &&
                urlBuilder.advanceSearch == -1 &&
                urlBuilder.minRating == -1 &&
                urlBuilder.pageFrom == -1 &&
                urlBuilder.pageTo == -1
            return when {
                ListUrlBuilder.MODE_NORMAL == urlBuilder.mode && noCondition ->
                    resources.getString(if (appName) R.string.app_name else R.string.homepage)
                MODE_SUBSCRIPTION == urlBuilder.mode && noCondition ->
                    resources.getString(R.string.subscription)
                MODE_WHATS_HOT == urlBuilder.mode -> resources.getString(R.string.whats_hot)
                MODE_TOPLIST == urlBuilder.mode -> when (urlBuilder.keyword) {
                    "11" -> resources.getString(R.string.toplist_alltime)
                    "12" -> resources.getString(R.string.toplist_pastyear)
                    "13" -> resources.getString(R.string.toplist_pastmonth)
                    "15" -> resources.getString(R.string.toplist_yesterday)
                    else -> null
                }
                !TextUtils.isEmpty(keyword) -> keyword
                hammingWeight(category) == 1 -> EhUtils.getCategory(category)
                else -> null
            }
        }

        /** 打开展示某个查询条件的列表页（上传者 / 分类 / tag / 关键字都走这里） */
        fun open(scene: Fragment, lub: ListUrlBuilder?) {
            scene.ehNavigator().push(EhRoute.GalleryList(galleryQueryOf(lub)))
        }
    }
}
