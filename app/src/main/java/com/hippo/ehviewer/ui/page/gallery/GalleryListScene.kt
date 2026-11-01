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
package com.hippo.ehviewer.ui.page.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.GravityCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.GalleryListScreen
import com.hippo.ehviewer.ui.compose.screen.GalleryListUiMode
import com.hippo.ehviewer.ui.compose.screen.GalleryListUiState
import com.hippo.ehviewer.ui.compose.screen.UrlSuggestionUi
import com.hippo.ehviewer.ui.compose.screen.parseUrlSuggestion
import com.hippo.ehviewer.ui.host.OnHostResume
import com.hippo.ehviewer.ui.host.ehHostOrNoOp

/**
 * 画廊列表页（首页 / 订阅 / 热门 / 榜单 / 任意搜索结果）。
 *
 * P7-c 出壳：状态、请求、监听、宿主副作用全部搬进 [GalleryListViewModel]，
 * 这里只剩「绑定 + 三个宿主交互」（返回键、日期选择器、去重后的抽屉勾选）。
 *
 * 与出壳前的差异：
 * - `onBackPressed` 的「双击退出」只在 [EhRoute] 处于栈底时生效（旧实现看 `stackIndex`）
 * - 「回到前台同步 listMode」改成 `OnHostResume`
 * - 图片搜索的选图器从 `registerForActivityResult` 换成 `rememberLauncherForActivityResult`
 * - 抽屉勾选项在页面内搜索换模式后由 `navCheckedRes` 就地推给宿主
 */
@Composable
fun GalleryListScene(route: EhRoute.GalleryList) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val fragmentActivity = context as? FragmentActivity
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val vm = viewModel { GalleryListViewModel(context.applicationContext) }

    LaunchedEffect(vm, route) { vm.start(route) }

    LaunchedEffect(vm.tip) {
        val tip = vm.consumeTip()
        if (tip != null) host.showTip(tip, vm.tipLength)
    }

    LaunchedEffect(vm.navCheckedRes) { host.setNavCheckedItem(vm.navCheckedRes) }

    OnHostResume { vm.syncListMode() }

    // 搜索态先退搜索；根页面再要求「双击退出」，非根页面交给 NavDisplay 直接弹栈
    BackHandler(enabled = vm.uiMode != GalleryListUiMode.NORMAL || navigator.isRoot) {
        if (vm.uiMode != GalleryListUiMode.NORMAL) {
            vm.exitSearch()
        } else if (vm.shouldExitAtRoot()) {
            navigator.pop()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        vm.setImageUri(uri)
    }

    fun showGoToDateDialog() {
        val range = vm.goToDateRange()
        val constraints = CalendarConstraints.Builder()
            .setStart(range.first)
            .setEnd(range.last)
            .setValidator(
                CompositeDateValidator.allOf(
                    listOf(
                        DateValidatorPointForward.from(range.first),
                        DateValidatorPointBackward.before(range.last),
                    ),
                ),
            )
            .build()
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setCalendarConstraints(constraints)
            .setTitleText(R.string.go_to)
            .setSelection(vm.goToDateDefault())
            .build()
        datePicker.addOnPositiveButtonClickListener { millis -> vm.goToDate(millis) }
        datePicker.show(fragmentActivity?.supportFragmentManager ?: return, "date-picker")
    }

    fun openUrlSuggestion() {
        when (val suggestion = parseUrlSuggestion(vm.keywordQuery)) {
            is UrlSuggestionUi.Detail -> navigator.push(
                EhRoute.GalleryDetail(gid = suggestion.gid, token = suggestion.token),
            )
            is UrlSuggestionUi.Page -> navigator.push(
                EhRoute.Progress(gid = suggestion.gid, pToken = suggestion.pToken, page = suggestion.page),
            )
            null -> return
        }
        vm.exitSearch()
    }

    GalleryListScreen(
        state = vm.uiState(),
        searchPanel = vm.searchPanel,
        query = vm.keywordQuery,
        onQueryChange = vm::onQueryChange,
        onOpenNavDrawer = { host.toggleDrawer(GravityCompat.START) },
        onEnterSimpleSearch = vm::enterSimpleSearch,
        onEnterSearch = vm::enterSearch,
        onExitSearch = vm::exitSearch,
        onApplySearch = vm::applySearch,
        onSuggestionClick = { if (parseUrlSuggestion(vm.keywordQuery) != null) openUrlSuggestion() },
        onPickImage = {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onClearImage = { vm.setImageUri(null) },
        onRefresh = vm::refresh,
        onLoadMore = vm::loadMore,
        onOpenDetail = { gi -> navigator.push(EhRoute.GalleryDetail(gid = gi.gid, token = gi.token)) },
        onRead = { gi -> navigator.push(EhRoute.Reader(gid = gi.gid, token = gi.token)) },
        onDownload = { gi -> activity?.let { vm.downloadGallery(gi, it) } },
        onDeleteDownload = vm::deleteDownload,
        onToggleFavorite = { gi -> activity?.let { vm.toggleFavorite(gi, it) } },
        onMoveDownloadLabel = vm::moveDownloadLabel,
        onToggleLayoutMode = vm::toggleLayoutMode,
        onGoToDate = ::showGoToDateDialog,
        onGoToLastPage = vm::goToLastPage,
        onGoToPage = vm::goToPage,
        onGoToGid = vm::goToGid,
        onSaveQuickSearch = vm::saveQuickSearch,
        onSelectQuickSearch = vm::selectQuickSearch,
        onDeleteQuickSearch = vm::deleteQuickSearch,
        onMoveQuickSearch = vm::moveQuickSearch,
        onSelectTopListRange = vm::selectTopListRange,
        downloadLabelOptions = vm.downloadLabels,
        pageCount = vm.topListPages,
    )
}

/** 把一个不可变快照交给界面层；`version` 用来感知 `GalleryInfo` 的就地修改 */
private fun GalleryListViewModel.uiState(): GalleryListUiState = GalleryListUiState(
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
