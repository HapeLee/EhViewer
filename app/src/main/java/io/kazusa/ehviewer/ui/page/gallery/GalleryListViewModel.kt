/*
 * Copyright 2016 Hippo Seven
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
package io.kazusa.ehviewer.ui.page.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kazusa.ehviewer.EhApplication.Companion.favouriteStatusRouter
import io.kazusa.ehviewer.EhApplication.Companion.searchDatabase
import io.kazusa.ehviewer.EhDB
import io.kazusa.ehviewer.FavouriteStatusRouter
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhClient
import io.kazusa.ehviewer.client.EhRequest
import io.kazusa.ehviewer.client.EhTagDatabase
import io.kazusa.ehviewer.client.EhUtils
import io.kazusa.ehviewer.client.data.GalleryInfo
import io.kazusa.ehviewer.client.data.ListUrlBuilder
import io.kazusa.ehviewer.client.data.ListUrlBuilder.Companion.MODE_SUBSCRIPTION
import io.kazusa.ehviewer.client.data.ListUrlBuilder.Companion.MODE_TOPLIST
import io.kazusa.ehviewer.client.data.ListUrlBuilder.Companion.MODE_WHATS_HOT
import io.kazusa.ehviewer.client.parser.GalleryListParser
import io.kazusa.ehviewer.dao.DownloadInfo
import io.kazusa.ehviewer.dao.QuickSearch
import io.kazusa.ehviewer.download.DownloadManager
import io.kazusa.ehviewer.download.DownloadManager.DownloadInfoListener
import io.kazusa.ehviewer.support.unifile.UniFile
import io.kazusa.ehviewer.support.unifile.sha1
import io.kazusa.ehviewer.support.util.ExceptionUtils
import io.kazusa.ehviewer.support.util.launchIO
import io.kazusa.ehviewer.support.util.toEpochMillis
import io.kazusa.ehviewer.support.util.toLocalDateTime
import io.kazusa.ehviewer.support.util.withUIContext
import io.kazusa.ehviewer.support.yorozuya.MathUtils.hammingWeight
import io.kazusa.ehviewer.ui.CommonOperations
import io.kazusa.ehviewer.ui.MainActivity
import io.kazusa.ehviewer.ui.compose.component.GalleryItemLayout
import io.kazusa.ehviewer.ui.compose.component.SearchCriteria
import io.kazusa.ehviewer.ui.compose.component.SearchPanelState
import io.kazusa.ehviewer.ui.compose.component.SearchSuggestionType
import io.kazusa.ehviewer.ui.compose.component.SearchSuggestionUi
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_HOMEPAGE
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_SUBSCRIPTION
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_TOP_LIST
import io.kazusa.ehviewer.ui.compose.nav.LIST_ACTION_WHATS_HOT
import io.kazusa.ehviewer.ui.compose.nav.toListUrlBuilder
import io.kazusa.ehviewer.ui.compose.screen.GalleryListUiMode
import io.kazusa.ehviewer.ui.compose.screen.TopListRange
import io.kazusa.ehviewer.ui.host.TIP_LENGTH_LONG
import io.kazusa.ehviewer.ui.host.TIP_LENGTH_SHORT
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * 画廊列表页的状态、请求与宿主副作用。
 *
 * 出壳前这些都住在 `GalleryListScene`（`SceneFragment`）里，`onSaveInstanceState` 存
 * `ListUrlBuilder` / `uiMode` / 榜单页码。现在：
 *
 * - 初始查询条件来自 [EhRoute.GalleryList]，之后的「搜索 / 换榜单范围」就地改
 *   [urlBuilder] 并重发请求（与旧行为一致：不换页面、不叠栈）
 * - 收藏槽位与下载状态仍然「就地改 `GalleryInfo` + 自增 [version]」，
 *   由场景把版本号带进快照触发重组
 * - 下载监听、收藏状态广播仍在这里注册，改在 [onCleared] 反注册（原来是 `onDestroy`）
 */
class GalleryListViewModel(private val appContext: Context) : ViewModel() {

    private var urlBuilder = ListUrlBuilder()

    var isTopList by mutableStateOf(false)
        private set
    var isPopular by mutableStateOf(false)
        private set

    var items by mutableStateOf(emptyList<GalleryInfo>(), neverEqualPolicy())
        private set
    var version by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var emptyText by mutableStateOf<String?>(null)
        private set
    var uiMode by mutableIntStateOf(GalleryListUiMode.NORMAL)
        private set
    var title by mutableStateOf("")
        private set
    var searchHint by mutableStateOf("")
        private set
    var keywordQuery by mutableStateOf("")
        private set
    var layoutMode by mutableIntStateOf(Settings.listMode)
        private set
    var quickSearches by mutableStateOf(emptyList<QuickSearch>())
        private set
    var downloadLabels by mutableStateOf(listOf<String>())
        private set
    var topListPages by mutableIntStateOf(0)
        private set
    var topListPage by mutableIntStateOf(1)
        private set
    var selectedTopListRange by mutableStateOf<String?>(null)
        private set
    var searchPanel by mutableStateOf(SearchPanelState())
        private set

    /** 搜索联想（历史 + 标签），由 [onQueryChange] 去抖刷新 */
    var searchSuggestions by mutableStateOf(emptyList<SearchSuggestionUi>())
        private set
    private var suggestionJob: Job? = null

    /** 一次性提示 */
    var tip by mutableStateOf<String?>(null)
        private set
    var tipLength by mutableIntStateOf(TIP_LENGTH_LONG)
        private set

    /** 抽屉勾选项：`finishSearch()` 换模式后就地更新，不依赖 route 变化 */
    var navCheckedRes by mutableIntStateOf(0)
        private set

    private var nextIndex: String? = null
    private var nextTopListPage = 0
    private var taskId = 0
    private var pressBackTime = 0L
    private var started = false

    private val downloadInfoListener: DownloadInfoListener = object : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) = bumpVersion()
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

    init {
        DownloadManager.addDownloadInfoListener(downloadInfoListener)
        favouriteStatusRouter.addListener(favouriteStatusRouterListener)
    }

    override fun onCleared() {
        DownloadManager.removeDownloadInfoListener(downloadInfoListener)
        favouriteStatusRouter.removeListener(favouriteStatusRouterListener)
    }

    fun start(route: EhRoute.GalleryList) {
        if (started) return
        started = true
        applyRoute(route)
        isTopList = urlBuilder.mode == MODE_TOPLIST
        isPopular = urlBuilder.mode == MODE_WHATS_HOT
        keywordQuery = urlBuilder.keyword.orEmpty()
        selectedTopListRange = urlBuilder.keyword
        searchPanel = newSearchPanel()
        updateHeader()
        refreshDownloadLabels()
        loadQuickSearches()
        navCheckedRes = navCheckedItemFor(urlBuilder.mode)
        refresh()
    }

    /** 回到前台时同步列表 / 网格模式（设置页可能刚改过） */
    fun syncListMode() {
        layoutMode = Settings.listMode
    }

    fun onQueryChange(query: String) {
        keywordQuery = query
        scheduleSuggestions(query)
    }

    fun enterSimpleSearch() {
        keywordQuery = urlBuilder.keyword.orEmpty()
        uiMode = GalleryListUiMode.SIMPLE_SEARCH
        // 刚打开搜索界面时关键词通常为空 → 直接给最近的历史，和旧 SearchBar 一致
        scheduleSuggestions(keywordQuery)
    }

    fun enterSearch() {
        searchPanel = newSearchPanel()
        keywordQuery = urlBuilder.keyword.orEmpty()
        uiMode = GalleryListUiMode.SEARCH
        scheduleSuggestions(keywordQuery)
    }

    fun exitSearch() {
        uiMode = GalleryListUiMode.NORMAL
        suggestionJob?.cancel()
        searchSuggestions = emptyList()
    }

    /**
     * 搜索词联想：搜索历史（[searchDatabase]）+ 标签库（[EhTagDatabase]），顺序与旧的
     * `io.kazusa.ehviewer.widget.SearchBar` 一致——历史在前，标签在后。
     *
     * 每敲一个字就整表扫一遍标签库在 10 万条量级上很贵，这里加 80ms 去抖并取消上一个 job。
     */
    private fun scheduleSuggestions(query: String) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launchIO {
            delay(SUGGESTION_DEBOUNCE_MS)
            val list = buildSuggestions(query)
            withUIContext { searchSuggestions = list }
        }
    }

    private suspend fun buildSuggestions(text: String): List<SearchSuggestionUi> {
        val list = ArrayList<SearchSuggestionUi>()
        runCatching { searchDatabase.getSuggestions(text, HISTORY_SUGGESTION_LIMIT) }
            .getOrNull()
            ?.forEach { list.add(SearchSuggestionUi(it, null, SearchSuggestionType.HISTORY)) }
        // 标签只在「最后一段」上联想，且当前词没写完（带空格说明已经开始下一个词）
        if (text.isNotEmpty() && !text.endsWith(" ") && EhTagDatabase.isInitialized()) {
            val keyword = text.substringAfterLast(' ')
            val translate = Settings.showTagTranslations && EhTagDatabase.isTranslatable(appContext)
            for (type in TAG_SUGGESTION_TYPES) {
                EhTagDatabase.suggestFlow(keyword, translate, type).collect { (hint, tag) ->
                    list.add(SearchSuggestionUi(tag, hint, SearchSuggestionType.TAG))
                }
            }
        }
        return list.take(SUGGESTION_LIMIT)
    }

    /** 点联想：历史直接替换整句；标签补全到最后一个词的位置（带引号 + 尾随空格） */
    fun selectSuggestion(suggestion: SearchSuggestionUi) {
        keywordQuery = when (suggestion.type) {
            SearchSuggestionType.HISTORY -> suggestion.keyword
            SearchSuggestionType.TAG -> {
                val head = keywordQuery.substringBeforeLast(' ', "")
                val word = wrapTagKeyword(suggestion.keyword)
                if (head.isEmpty()) word else "$head $word"
            }
        }
        scheduleSuggestions(keywordQuery)
    }

    /** 历史条目长按删除 */
    fun deleteSuggestion(suggestion: SearchSuggestionUi) {
        if (suggestion.type != SearchSuggestionType.HISTORY) return
        viewModelScope.launchIO {
            runCatching { searchDatabase.deleteQuery(suggestion.keyword) }
            val list = buildSuggestions(keywordQuery)
            withUIContext { searchSuggestions = list }
        }
    }

    /**
     * 根页面的返回键：两秒内按两次才退出。
     *
     * @return true 表示这次该退出页面
     */
    fun shouldExitAtRoot(): Boolean {
        if (uiMode != GalleryListUiMode.NORMAL) {
            uiMode = GalleryListUiMode.NORMAL
            return false
        }
        val time = System.currentTimeMillis()
        if (time - pressBackTime > BACK_PRESSED_INTERVAL) {
            pressBackTime = time
            showTip(appContext.getString(R.string.press_twice_exit), TIP_LENGTH_SHORT)
            return false
        }
        return true
    }

    fun consumeTip(): String? = tip.also { tip = null }

    // region 分页与请求

    fun refresh() {
        error = null
        emptyText = null
        if (isTopList) {
            requestTopList(1, false)
        } else {
            requestCursor(null, null, true, false)
        }
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        if (isTopList) {
            requestTopList(nextTopListPage, true)
        } else {
            requestCursor(nextIndex, null, true, true)
        }
    }

    fun goToLastPage() {
        requestCursor("1-0", null, false, false)
    }

    fun goToPage(page: Int) {
        requestTopList(page, false)
    }

    fun goToGid(gid: Long) {
        requestCursor((gid + 1).toString(), null, gid != 1L, false)
    }

    fun goToDate(millis: Long) {
        requestCursor(null, millis.toLocalDateTime().date.toString(), true, false)
    }

    /** 日期选择器的合法区间：2007-03-21 到昨天 */
    fun goToDateRange(): LongRange {
        val start = LocalDate(2007, 3, 21).toEpochMillis()
        val end = Clock.System.todayIn(TimeZone.UTC).minus(1, DateTimeUnit.DAY).toEpochMillis()
        return start until end
    }

    fun goToDateDefault(): Long = goToDateRange().last

    private fun requestCursor(index: String?, seek: String?, isNext: Boolean, append: Boolean) {
        if (!isTopList) {
            urlBuilder.setJumpTo(seek)
            urlBuilder.setIndex(index, isNext)
        }
        val url = urlBuilder.build()
        urlBuilder.setJumpTo(null)
        urlBuilder.setIndex(null, true)
        val tid = ++taskId
        if (append) loadingMore = true else loading = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
            .setArgs(url)
            .setCallback(
                GalleryListListener(tid, append, topListRequest = false, page = 0),
            )
            .enqueue(viewModelScope)
    }

    private fun requestTopList(page: Int, append: Boolean) {
        urlBuilder.setJumpTo(page.toString())
        val url = urlBuilder.build()
        urlBuilder.setJumpTo(null)
        val tid = ++taskId
        if (append) loadingMore = true else loading = true
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_LIST)
            .setArgs(url)
            .setCallback(
                GalleryListListener(tid, append, topListRequest = true, page = page),
            )
            .enqueue(viewModelScope)
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
            appContext.getString(R.string.gallery_list_empty_hit_subscription)
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
            showTip(message)
        } else {
            error = message
        }
    }

    // endregion

    // region 搜索

    fun applySearch(query: String) {
        // 记进搜索历史，下次联想时能出来（旧 SearchBar 也是在这里写的）
        if (query.isNotBlank()) {
            viewModelScope.launchIO { runCatching { searchDatabase.addQuery(query) } }
        }
        if (uiMode == GalleryListUiMode.SEARCH) {
            when (val criteria = searchPanel.buildCriteria(query)) {
                is SearchCriteria.Failure -> {
                    showTip(appContext.getString(criteria.messageRes))
                    return
                }
                is SearchCriteria.Success -> {
                    urlBuilder.reset()
                    urlBuilder.mode = criteria.mode
                    urlBuilder.category = criteria.category
                    urlBuilder.keyword = criteria.keyword
                    urlBuilder.language = criteria.language
                    urlBuilder.advanceSearch = criteria.advanceSearch
                    urlBuilder.minRating = criteria.minRating
                    urlBuilder.pageFrom = criteria.pageFrom
                    urlBuilder.pageTo = criteria.pageTo
                }
            }
        } else {
            // 简单搜索：与旧实现一致，只保留「订阅搜索」模式，其余条件全部重置
            val newMode = if (urlBuilder.mode == MODE_SUBSCRIPTION) MODE_SUBSCRIPTION else ListUrlBuilder.MODE_NORMAL
            urlBuilder.reset()
            urlBuilder.mode = newMode
            urlBuilder.keyword = query
        }
        finishSearch()
    }

    /**
     * 以图搜图：搜索栏右上角的图标选完图就直接搜。
     *
     * 面板里不再有「图片搜索」页签与预览区（对齐参考实现：筛选面板只放筛条件），
     * 所以选图这一步由搜索栏发起，算完 SHA-1 立刻刷新列表。
     */
    fun searchByImage(uri: Uri?) {
        if (uri == null) {
            showTip(appContext.getString(R.string.select_image_first))
            return
        }
        viewModelScope.launchIO {
            val hash = UniFile.fromUri(appContext, uri)?.sha1()
            withUIContext {
                if (hash == null) {
                    showTip(appContext.getString(R.string.select_image_first))
                } else {
                    urlBuilder.reset()
                    urlBuilder.mode = ListUrlBuilder.MODE_IMAGE_SEARCH
                    urlBuilder.hash = hash
                    finishSearch()
                }
            }
        }
    }

    private fun finishSearch() {
        isTopList = urlBuilder.mode == MODE_TOPLIST
        isPopular = urlBuilder.mode == MODE_WHATS_HOT
        selectedTopListRange = urlBuilder.keyword
        uiMode = GalleryListUiMode.NORMAL
        updateHeader()
        navCheckedRes = navCheckedItemFor(urlBuilder.mode)
        refresh()
    }

    // endregion

    // region 条目操作

    fun downloadGallery(gi: GalleryInfo, activity: MainActivity) {
        CommonOperations.startDownload(activity, gi, false)
    }

    fun deleteDownload(gi: GalleryInfo) {
        viewModelScope.launch { DownloadManager.deleteDownload(gi.gid) }
    }

    fun toggleFavorite(gi: GalleryInfo, activity: MainActivity) {
        val listener = FavoriteChangeListener(gi.gid)
        if (gi.favoriteSlot != -2) {
            CommonOperations.removeFromFavorites(activity, gi, listener)
        } else {
            CommonOperations.addToFavorites(activity, gi, listener, false)
        }
    }

    fun moveDownloadLabel(gi: GalleryInfo, index: Int) {
        val info = DownloadManager.getDownloadInfo(gi.gid) ?: return
        val label = if (index <= 0) null else downloadLabels.getOrNull(index)
        viewModelScope.launch { DownloadManager.changeLabel(listOf(info), label) }
    }

    fun toggleLayoutMode() {
        val next = if (layoutMode == GalleryItemLayout.GRID) GalleryItemLayout.LIST else GalleryItemLayout.GRID
        Settings.putListMode(next)
        layoutMode = next
    }

    private fun refreshDownloadLabels() {
        val labels = mutableListOf(appContext.getString(R.string.default_download_label_name))
        DownloadManager.labelList.forEach { it.label?.let(labels::add) }
        downloadLabels = labels
    }

    // endregion

    // region 榜单

    fun selectTopListRange(id: String) {
        Settings.putDefaultTopList(id)
        urlBuilder.keyword = id
        selectedTopListRange = id
        updateHeader()
        refresh()
    }

    fun topListRanges(): List<TopListRange> = listOf(
        TopListRange("15", appContext.getString(R.string.toplist_yesterday)),
        TopListRange("13", appContext.getString(R.string.toplist_pastmonth)),
        TopListRange("12", appContext.getString(R.string.toplist_pastyear)),
        TopListRange("11", appContext.getString(R.string.toplist_alltime)),
    )

    // endregion

    // region 快速搜索

    private fun loadQuickSearches() {
        viewModelScope.launchIO {
            val list = EhDB.allQuickSearch
            withUIContext { quickSearches = list }
        }
    }

    fun selectQuickSearch(quickSearch: QuickSearch) {
        urlBuilder.set(quickSearch)
        isTopList = false
        isPopular = false
        val name = quickSearch.name.orEmpty()
        val anchor = name.lastIndexOf('@')
        val seek = if (anchor != -1) name.substring(anchor + 1) else null
        keywordQuery = urlBuilder.keyword.orEmpty()
        uiMode = GalleryListUiMode.NORMAL
        updateHeader()
        navCheckedRes = navCheckedItemFor(urlBuilder.mode)
        requestCursor(null, seek, true, false)
    }

    fun saveQuickSearch(name: String, saveProgress: Boolean, anchorGid: Long?) {
        if (urlBuilder.mode == ListUrlBuilder.MODE_IMAGE_SEARCH) {
            showTip(appContext.getString(R.string.image_search_not_quick_search))
            return
        }
        if (name.isEmpty()) {
            showTip(appContext.getString(R.string.name_is_empty))
            return
        }
        val anchor = anchorGid?.let { "@" + (it + 1) }
        // 与旧实现一致：条件完全相同的快速搜索且进度锚点也一致时才提示重复
        for (existing in quickSearches) {
            if (!urlBuilder.equalsQuickSearch(existing)) continue
            val existingName = existing.name.orEmpty()
            val at = existingName.lastIndexOf('@')
            if (at != -1 && anchor != null && existingName.substring(at) == anchor) {
                showTip(appContext.getString(R.string.duplicate_quick_search, existingName))
                return
            }
        }
        Settings.putQSSaveProgress(saveProgress)
        val text = if (saveProgress && anchor != null) name + anchor else name
        if (quickSearches.any { it.name == text }) {
            showTip(appContext.getString(R.string.duplicate_name))
            return
        }
        val quickSearch = urlBuilder.toQuickSearch()
        quickSearch.name = text
        viewModelScope.launchIO {
            EhDB.insertQuickSearch(quickSearch)
            val list = EhDB.allQuickSearch
            withUIContext { quickSearches = list }
        }
    }

    fun deleteQuickSearch(quickSearch: QuickSearch) {
        quickSearches = quickSearches.filter { it.id != quickSearch.id }
        viewModelScope.launchIO {
            EhDB.deleteQuickSearch(quickSearch)
        }
    }

    fun moveQuickSearch(from: Int, to: Int) {
        if (from == to) return
        val list = quickSearches.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        quickSearches = list
        viewModelScope.launchIO {
            EhDB.moveQuickSearch(from, to)
        }
    }

    // endregion

    // region 头信息

    private fun applyRoute(route: EhRoute.GalleryList) {
        val query = route.query
        urlBuilder = when (query.action) {
            LIST_ACTION_SUBSCRIPTION -> ListUrlBuilder(MODE_SUBSCRIPTION)
            LIST_ACTION_WHATS_HOT -> ListUrlBuilder(MODE_WHATS_HOT)
            LIST_ACTION_TOP_LIST -> ListUrlBuilder(MODE_TOPLIST).apply { keyword = Settings.defaultTopList }
            LIST_ACTION_HOMEPAGE -> ListUrlBuilder()
            else -> query.toListUrlBuilder().copy()
        }
    }

    private fun newSearchPanel(): SearchPanelState {
        val mode = urlBuilder.mode
        return SearchPanelState(
            searchMode = if (mode == ListUrlBuilder.MODE_IMAGE_SEARCH || mode == MODE_WHATS_HOT || mode == MODE_TOPLIST) {
                ListUrlBuilder.MODE_NORMAL
            } else {
                mode
            },
            category = urlBuilder.category,
            language = urlBuilder.language,
            advanceSearch = urlBuilder.advanceSearch.coerceAtLeast(0),
            minRating = urlBuilder.minRating,
            pageFrom = urlBuilder.pageFrom.takeIf { it != -1 }?.toString().orEmpty(),
            pageTo = urlBuilder.pageTo.takeIf { it != -1 }?.toString().orEmpty(),
        )
    }

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
        title = suitableTitle(appContext.resources, urlBuilder) ?: urlBuilder.keyword.orEmpty()
        searchHint = appContext.getString(
            if (EhUtils.isExHentai) {
                R.string.gallery_list_search_bar_hint_exhentai
            } else {
                R.string.gallery_list_search_bar_hint_e_hentai
            },
        )
    }

    // endregion

    private fun bumpVersion() {
        version++
    }

    private fun showTip(message: String, length: Int = TIP_LENGTH_LONG) {
        tip = message
        tipLength = length
    }

    private inner class GalleryListListener(
        private val taskId: Int,
        private val append: Boolean,
        private val topListRequest: Boolean,
        private val page: Int,
    ) : EhClient.Callback<GalleryListParser.Result> {
        override fun onSuccess(result: GalleryListParser.Result) {
            if (taskId != this@GalleryListViewModel.taskId) return
            applyPage(result, append, if (topListRequest) page else 0)
        }

        override fun onFailure(e: Exception) {
            if (taskId != this@GalleryListViewModel.taskId) return
            onLoadFailure(e, append)
        }

        override fun onCancel() {
            loading = false
            loadingMore = false
        }
    }

    private inner class FavoriteChangeListener(private val gid: Long) : EhClient.Callback<Unit> {
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

    private companion object {
        const val TAG = "GalleryListViewModel"
        const val BACK_PRESSED_INTERVAL = 2000L
    }
}

/** 列表页标题；语义与出壳前的 `getSuitableTitleForUrlBuilder` 一致 */
private fun suitableTitle(resources: android.content.res.Resources, urlBuilder: ListUrlBuilder): String? {
    val keyword = urlBuilder.keyword
    val category = urlBuilder.category
    val noCondition = EhUtils.NONE == category &&
        keyword.isNullOrEmpty() &&
        urlBuilder.advanceSearch == -1 &&
        urlBuilder.minRating == -1 &&
        urlBuilder.pageFrom == -1 &&
        urlBuilder.pageTo == -1
    return when {
        ListUrlBuilder.MODE_NORMAL == urlBuilder.mode && noCondition ->
            resources.getString(R.string.homepage)
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
        !keyword.isNullOrEmpty() -> keyword
        hammingWeight(category) == 1 -> EhUtils.getCategory(category)
        else -> null
    }
}

/** `ListUrlBuilder` 模式的抽屉勾选项 */
private fun navCheckedItemFor(mode: Int): Int = when (mode) {
    ListUrlBuilder.MODE_NORMAL -> R.id.nav_homepage
    MODE_SUBSCRIPTION -> R.id.nav_subscription
    MODE_WHATS_HOT -> R.id.nav_whats_hot
    MODE_TOPLIST -> R.id.nav_toplist
    else -> 0
}

private const val SUGGESTION_DEBOUNCE_MS = 80L
private const val HISTORY_SUGGESTION_LIMIT = 32
private const val SUGGESTION_LIMIT = 64

/** 标签联想的匹配档位：完全相等 → 前缀 → 包含，和旧 SearchBar 的遍历顺序一致 */
private val TAG_SUGGESTION_TYPES = intArrayOf(
    EhTagDatabase.TYPE_EQUAL,
    EhTagDatabase.TYPE_START,
    EhTagDatabase.TYPE_CONTAIN,
)
