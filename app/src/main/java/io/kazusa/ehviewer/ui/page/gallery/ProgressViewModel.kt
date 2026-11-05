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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.EhClient
import io.kazusa.ehviewer.client.EhRequest
import io.kazusa.ehviewer.support.util.ExceptionUtils
import io.kazusa.ehviewer.ui.compose.nav.EhRoute

/**
 * 进度中转页的状态：拿 `gid + pToken + page` 换一个新 token，成功就跳到详情页。
 *
 * 出壳前这两态是 `SceneFragment` 的 Compose state，旋转时靠 `onSaveInstanceState`
 * 存参数、`onCreate` 里重跑一次请求。改成 ViewModel 之后旋转不再重发请求，
 * 请求本身跟着 [viewModelScope] 走：页面被 `replaceTop` 换掉时一起取消。
 */
class ProgressViewModel(private val appContext: Context) : ViewModel() {

    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 换到的 token，一次性：UI 消费后调用 [consumeToken] */
    var token by mutableStateOf<String?>(null)
        private set

    private var route: EhRoute.Progress? = null
    private var started = false

    /**
     * 入口只生效一次：旋转重组时参数没变，不会重复请求。
     *
     * 参数确实变了（同槽位换参数）就当成新任务重跑，避免拿旧 token 跳到错误的分页。
     */
    fun start(route: EhRoute.Progress) {
        if (started && route == this.route) return
        this.route = route
        started = true
        run()
    }

    fun retry() {
        if (running) return
        run()
    }

    fun consumeToken(): String? = token.also { token = null }

    private fun run() {
        val route = route ?: return fail()
        val pToken = route.pToken ?: return fail()
        if (route.gid == -1L || route.page < 0) return fail()
        running = true
        error = null
        val target = route
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_TOKEN)
            .setArgs(route.gid, pToken, route.page)
            .setCallback(
                object : EhClient.Callback<String> {
                    override fun onSuccess(result: String) {
                        // 参数已经换过：这次结果作废，新的那次请求说了算
                        if (target != this@ProgressViewModel.route) return
                        running = false
                        token = result
                    }

                    override fun onFailure(e: Exception) {
                        if (target != this@ProgressViewModel.route) return
                        running = false
                        error = ExceptionUtils.getReadableString(e)
                    }

                    override fun onCancel() {
                        if (target != this@ProgressViewModel.route) return
                        running = false
                    }
                },
            )
            .enqueue(viewModelScope)
    }

    private fun fail() {
        running = false
        error = appContext.getString(R.string.error_something_wrong_happened)
    }
}
