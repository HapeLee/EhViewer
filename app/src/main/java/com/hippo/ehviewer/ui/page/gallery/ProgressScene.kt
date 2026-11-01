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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.ProgressScreen

/**
 * 进度中转页：分页链接 → 换 token → 详情页。
 *
 * P7-c 出壳后不再有 `arguments` / `onSaveInstanceState`：参数直接来自
 * [EhRoute.Progress]，状态与请求在 [ProgressViewModel] 里，旋转不重发。
 *
 * 换到 token 后仍然用 `replaceTop` 而不是 `push`：返回键不该退回「正在处理」这一页。
 */
@Composable
fun ProgressScene(route: EhRoute.Progress) {
    val context = LocalContext.current
    val navigator = ehNavigator()
    val vm = viewModel { ProgressViewModel(context.applicationContext) }

    LaunchedEffect(vm, route) { vm.start(route) }

    LaunchedEffect(vm.token) {
        val token = vm.consumeToken() ?: return@LaunchedEffect
        navigator.replaceTop(
            EhRoute.GalleryDetail(gid = route.gid, token = token, page = route.page),
        )
    }

    ProgressScreen(
        running = vm.running,
        error = vm.error,
        onRetry = vm::retry,
    )
}
