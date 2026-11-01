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

import androidx.activity.compose.BackHandler
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
import com.hippo.ehviewer.ui.compose.screen.FavoritesScreen
import com.hippo.ehviewer.ui.host.OnHostResume
import com.hippo.ehviewer.ui.host.ehHostOrNoOp

/**
 * 收藏页。
 *
 * P7-c 出壳：页面本身只是 `FavoritesViewModel` 的一层绑定。
 * 状态与网络逻辑托管给 ViewModel（`rememberViewModelStoreNavEntryDecorator` 保证
 * 离开返回栈时才 clear），因此旋转不会重建列表——旧 Fragment 也是这个行为。
 *
 * 与旧实现的差异只有三处：
 * - `EhRequest.enqueue(this)` 改成 `enqueue(viewModelScope)`
 * - `getString` / `context` 换成 `appContext`（ViewModel 里）
 * - 「列表非空时用 Snackbar 提示」改成一次性 `tip` 状态，由这里消费
 *
 * 日期选择器仍用 `MaterialDatePicker`（走宿主 Activity 的 `supportFragmentManager`），
 * 避免在出壳这一片里同时换掉交互形态。
 */
@Composable
fun FavoritesScene() {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val vm = viewModel { FavoritesViewModel(context.applicationContext) }

    LaunchedEffect(vm.tip) {
        val tip = vm.consumeTip()
        if (tip != null) {
            host.showTip(tip, vm.tipLength)
        }
    }

    OnHostResume { vm.syncListMode() }

    // 多选 / 搜索状态下返回键先退这两层，不弹页面
    BackHandler(enabled = vm.selection.isNotEmpty() || vm.searchMode) {
        when {
            vm.selection.isNotEmpty() -> vm.clearSelection()
            else -> vm.exitSearch()
        }
    }

    fun showGoToDialog() {
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
        datePicker.show(activity?.supportFragmentManager ?: return, "date-picker")
    }

    FavoritesScreen(
        title = vm.title,
        searchHint = vm.searchHint,
        items = vm.items,
        listMode = vm.listMode,
        loading = vm.loading,
        loadingMore = vm.loadingMore,
        hasMore = vm.hasMore,
        error = vm.error,
        searchMode = vm.searchMode,
        appliedKeyword = vm.appliedKeyword,
        categories = vm.categories,
        currentFavCat = vm.currentFavCat,
        selection = vm.selection,
        onOpenNavDrawer = { host.toggleDrawer(GravityCompat.START) },
        onRefresh = vm::refresh,
        onLoadMore = vm::loadMore,
        onOpenDetail = { gi -> navigator.push(EhRoute.GalleryDetail(gid = gi.gid, token = gi.token)) },
        onToggleSelect = vm::toggleSelect,
        onSelectAll = vm::selectAll,
        onClearSelection = vm::clearSelection,
        onDownloadSelected = { vm.downloadSelected(activity as? MainActivity) },
        onDeleteSelected = vm::deleteSelected,
        onMoveSelected = vm::moveSelected,
        onEnterSearch = vm::enterSearch,
        onExitSearch = vm::exitSearch,
        onApplySearch = vm::applySearch,
        onSelectCategory = vm::selectCategory,
        onSetDefaultSlot = vm::setDefaultSlot,
        onGoToDate = ::showGoToDialog,
        onGoToLastPage = vm::goToLastPage,
    )
}
