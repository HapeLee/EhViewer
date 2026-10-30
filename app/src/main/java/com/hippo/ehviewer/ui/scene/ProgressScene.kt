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
package com.hippo.ehviewer.ui.scene

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.ProgressScreen
import com.hippo.util.ExceptionUtils

/**
 * Only show a progress with jobs in background
 *
 * 已迁移到 Compose：状态用 Compose state 表达，取 token 成功后照旧跳到 [GalleryDetailScene]。
 */
class ProgressScene : EhComposeScene() {

    private var running by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var started = false

    private var action: String? = null
    private var gid: Long = 0
    private var pToken: String? = null
    private var page = 0

    override fun needShowLeftDrawer(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACTION, action)
        outState.putLong(KEY_GID, gid)
        outState.putString(KEY_PTOKEN, pToken)
        outState.putInt(KEY_PAGE, page)
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.Progress> {
            ProgressScreen(
                running = running,
                error = error,
                onRetry = {
                    if (doJobs()) {
                        running = true
                        error = null
                    }
                },
            )
        }
    }

    private fun onInit() {
        if (!handleArgs(arguments) || !doJobs()) {
            running = false
            error = getString(R.string.error_something_wrong_happened)
        } else {
            running = true
        }
    }

    private fun onRestore(savedInstanceState: Bundle) {
        action = savedInstanceState.getString(KEY_ACTION)
        gid = savedInstanceState.getLong(KEY_GID, -1)
        pToken = savedInstanceState.getString(KEY_PTOKEN)
        page = savedInstanceState.getInt(KEY_PAGE, -1)
        // 旋转重建后重跑一次请求，代价很小；成功会直接跳转，不会停留
        if (!doJobs()) {
            error = getString(R.string.error_something_wrong_happened)
        } else {
            running = true
        }
    }

    private fun handleArgs(args: Bundle?): Boolean {
        if (args == null) {
            return false
        }
        action = args.getString(KEY_ACTION)
        if (ACTION_GALLERY_TOKEN == action) {
            gid = args.getLong(KEY_GID, -1)
            pToken = args.getString(KEY_PTOKEN, null)
            page = args.getInt(KEY_PAGE, -1)
            return gid != -1L && pToken != null && page != -1
        }
        return false
    }

    private fun doJobs(): Boolean {
        if (started) return running
        val context = context ?: return false
        if (mainActivity == null) return false
        if (ACTION_GALLERY_TOKEN == action) {
            if (gid == -1L || pToken == null || page == -1) {
                return false
            }
            started = true
            val request = EhRequest()
                .setMethod(EhClient.METHOD_GET_GALLERY_TOKEN)
                .setArgs(gid, pToken!!, page)
                .setCallback(GetGalleryTokenListener(context))
            request.enqueue()
            return true
        }
        return false
    }

    private fun onGetGalleryTokenSuccess(result: String) {
        // 换到 token 后把进度页换成详情页：replaceTop 让返回键不会退回"正在处理"的中间页
        ehNavigator().replaceTop(EhRoute.GalleryDetail(gid = gid, token = result, page = page))
    }

    private fun onGetGalleryTokenFailure(e: Exception) {
        started = false
        running = false
        error = ExceptionUtils.getReadableString(e)
    }

    private inner class GetGalleryTokenListener(
        context: Context,
    ) : EhCallback<ProgressScene, String>(context) {
        override fun onSuccess(result: String) {
            val scene = this@ProgressScene
            scene.onGetGalleryTokenSuccess(result)
        }

        override fun onFailure(e: Exception) {
            val scene = this@ProgressScene
            scene.onGetGalleryTokenFailure(e)
        }

        override fun onCancel() {}
    }

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_GALLERY_TOKEN = "gallery_token"
        const val KEY_GID = "gid"
        const val KEY_PTOKEN = "ptoken"
        const val KEY_PAGE = "page"
    }
}
