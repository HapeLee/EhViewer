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
package com.hippo.ehviewer.ui.page.history

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.view.GravityCompat
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.HistoryActions
import com.hippo.ehviewer.ui.compose.screen.HistoryScreen
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG
import com.hippo.ehviewer.ui.host.TIP_LENGTH_SHORT
import com.hippo.ehviewer.ui.host.ehHostOrNoOp
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import kotlinx.coroutines.launch

/**
 * 历史记录页。
 *
 * P7-c 出壳：不再是 Fragment，`onCreate` / `onDestroy` 里的监听注册改成
 * [DisposableEffect]，`lifecycleScope` 换成 `rememberCoroutineScope`。
 *
 * 迁移点（沿用 P7-a 以来的做法）：
 * - 分页仍走 Room 的 `historyLazyList`，由 `collectAsLazyPagingItems()` 消费
 * - 下载 / 收藏角标不是数据库字段，用 `dataVersion` 计数驱动重组
 * - 长按菜单各动作仍调用原来的 `DownloadManager` / `CommonOperations`
 */
@Composable
fun HistoryScene() {
    val context = LocalContext.current
    // 读资源用 LocalResources：LocalContext.current 不随 Configuration 变化失效
    val resources = LocalResources.current
    // 收藏 / 下载这些动作要的是宿主 Activity，不是泛化的 Context
    val activity = context as? MainActivity
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.history)
    val defaultLabel = stringResource(R.string.default_download_label_name)

    var dataVersion by remember { mutableIntStateOf(0) }

    val historyFlow = remember { Pager(PagingConfig(20)) { EhDB.historyLazyList }.flow }
    val downloadInfoListener = remember {
        object : DownloadInfoListener {
            override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                dataVersion++
            }

            override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>) {}

            override fun onUpdateAll() {}

            override fun onReload() {
                dataVersion++
            }

            override fun onChange() {
                dataVersion++
            }

            override fun onRenameLabel(from: String, to: String) {}

            override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
                dataVersion++
            }

            override fun onUpdateLabels() {}
        }
    }
    val favouriteStatusRouterListener = remember {
        FavouriteStatusRouter.Listener { _, _ -> dataVersion++ }
    }

    DisposableEffect(downloadInfoListener, favouriteStatusRouterListener) {
        DownloadManager.addDownloadInfoListener(downloadInfoListener)
        EhApplication.favouriteStatusRouter.addListener(favouriteStatusRouterListener)
        onDispose {
            DownloadManager.removeDownloadInfoListener(downloadInfoListener)
            EhApplication.favouriteStatusRouter.removeListener(favouriteStatusRouterListener)
        }
    }

    fun downloadLabels(): List<String> {
        val labelRawList = DownloadManager.labelList
        val labels = ArrayList<String>(labelRawList.size + 1)
        labels.add(defaultLabel)
        labelRawList.forEach { info -> info.label?.let { labels.add(it) } }
        return labels
    }

    fun moveLabel(gid: Long, index: Int) {
        val downloadInfo = DownloadManager.getDownloadInfo(gid) ?: return
        val labels = downloadLabels()
        if (index !in labels.indices) return
        // 下标 0 是默认标签，对应依赖里的 null
        scope.launch { DownloadManager.changeLabel(listOf(downloadInfo), if (index == 0) null else labels[index]) }
    }

    fun deleteHistory(gi: GalleryInfo) {
        scope.launchIO {
            val info = gi as? HistoryInfo ?: return@launchIO
            EhDB.deleteHistoryInfo(info)
        }
    }

    fun clearHistory() {
        scope.launchIO {
            EhDB.clearHistoryInfo()
            withUIContext { dataVersion++ }
        }
    }

    fun openReader(gi: GalleryInfo) {
        navigator.push(EhRoute.Reader(gid = gi.gid, token = gi.token))
    }

    fun openDetail(gi: GalleryInfo) {
        navigator.push(EhRoute.GalleryDetail(gid = gi.gid, token = gi.token))
    }

    val actions = remember(context, resources, navigator) {
        HistoryActions(
            isDownloaded = { gi -> DownloadManager.containDownloadInfo(gi.gid) },
            isFavourited = { gi -> gi.favoriteSlot != -2 },
            downloadLabels = { downloadLabels() },
            onRead = { gi -> openReader(gi) },
            onOpenDetail = { gi -> openDetail(gi) },
            onDownload = { gi -> activity?.let { CommonOperations.startDownload(it, gi, false) } },
            onRemoveDownload = { gi -> scope.launch { DownloadManager.deleteDownload(gi.gid) } },
            onToggleFavorite = { gi ->
                activity?.let { act ->
                    val remove = gi.favoriteSlot != -2
                    val listener = favoriteCallback({ resId, length -> host.showTip(resources.getString(resId), length) }, remove)
                    if (remove) {
                        CommonOperations.removeFromFavorites(act, gi, listener)
                    } else {
                        CommonOperations.addToFavorites(act, gi, listener, false)
                    }
                }
            },
            onDelete = { gi -> deleteHistory(gi) },
            onDeleteAll = { clearHistory() },
            onMoveLabel = { gi, index -> moveLabel(gi.gid, index) },
        )
    }

    val flow = remember(historyFlow, scope) { historyFlow.cachedIn(scope) }
    HistoryScreen(
        title = title,
        items = flow.collectAsLazyPagingItems(),
        actions = actions,
        version = dataVersion,
        onOpenDrawer = { host.toggleDrawer(GravityCompat.START) },
    )
}

/** 收藏 / 取消收藏的回调：只负责发提示，提示落地交给宿主 */
private fun favoriteCallback(onTip: (Int, Int) -> Unit, remove: Boolean): EhClient.Callback<Unit> = object : EhClient.Callback<Unit> {
    override fun onSuccess(result: Unit) {
        onTip(
            if (remove) R.string.remove_from_favorite_success else R.string.add_to_favorite_success,
            TIP_LENGTH_SHORT,
        )
    }

    override fun onFailure(e: Exception) {
        onTip(
            if (remove) R.string.remove_from_favorite_failure else R.string.add_to_favorite_failure,
            TIP_LENGTH_LONG,
        )
    }

    override fun onCancel() {}
}
