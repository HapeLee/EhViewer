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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.HistoryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.HistoryActions
import com.hippo.ehviewer.ui.compose.screen.HistoryScreen
import com.hippo.util.launchIO
import com.hippo.util.withUIContext

/**
 * 历史记录页。
 *
 * 迁移点：
 * - 分页仍走 Room 的 `historyLazyList`，但由 `collectAsLazyPagingItems()` 消费，
 *   `PagingDataAdapter`、`DiffUtil`、`ItemTouchHelper` 一并去掉
 * - 下载/收藏角标不是数据库里的字段，旧实现用 `notifyDataSetChanged` 全表刷新；
 *   这里用 [dataVersion] 计数驱动重组，效果等价
 * - 长按菜单里的读、下载、收藏、删除、移动标签仍调用原来的 `DownloadManager` /
 *   `CommonOperations`，只是入口换成 Compose 对话框
 * - 卡片进详情页不再带共享元素转场（`EnterGalleryDetailTransaction`），其余参数不变
 */
class HistoryScene : EhComposeScene() {

    private var dataVersion by mutableStateOf(0)
    private val historyFlow = Pager(PagingConfig(20)) { EhDB.historyLazyList }.flow

    private val downloadInfoListener: DownloadInfoListener by lazy {
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

    private val favouriteStatusRouterListener = FavouriteStatusRouter.Listener { _, _ ->
        dataVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.addDownloadInfoListener(downloadInfoListener)
        EhApplication.favouriteStatusRouter.addListener(favouriteStatusRouterListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadManager.removeDownloadInfoListener(downloadInfoListener)
        EhApplication.favouriteStatusRouter.removeListener(favouriteStatusRouterListener)
    }

    override fun getNavCheckedItem(): Int = R.id.nav_history

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.History> {
            val flow = remember { historyFlow.cachedIn(lifecycleScope) }
            val items = flow.collectAsLazyPagingItems()
            HistoryScreen(
                title = getString(R.string.history),
                items = items,
                actions = buildActions(),
                version = dataVersion,
                onOpenDrawer = { toggleDrawer(GravityCompat.START) },
            )
        }
    }

    private fun buildActions(): HistoryActions {
        val activity = mainActivity
        val context = context
        return HistoryActions(
            isDownloaded = { gi -> DownloadManager.containDownloadInfo(gi.gid) },
            isFavourited = { gi -> gi.favoriteSlot != -2 },
            downloadLabels = { downloadLabels() },
            onRead = { gi -> openReader(gi) },
            onOpenDetail = { gi -> openDetail(gi) },
            onDownload = { gi ->
                if (activity != null) {
                    CommonOperations.startDownload(activity, gi, false)
                }
            },
            onRemoveDownload = { gi -> DownloadManager.deleteDownload(gi.gid) },
            onToggleFavorite = { gi ->
                if (activity != null && context != null) {
                    if (gi.favoriteSlot != -2) {
                        CommonOperations.removeFromFavorites(
                            activity,
                            gi,
                            RemoveFromFavoriteListener(context),
                        )
                    } else {
                        CommonOperations.addToFavorites(
                            activity,
                            gi,
                            AddToFavoriteListener(context),
                            false,
                        )
                    }
                }
            },
            onDelete = { gi -> deleteHistory(gi) },
            onDeleteAll = { clearHistory() },
            onMoveLabel = { gi, index -> moveLabel(gi.gid, index) },
        )
    }

    private fun openReader(gi: GalleryInfo) {
        val ctx = context ?: return
        val intent = Intent(ctx, GalleryActivity::class.java).apply {
            action = GalleryActivity.ACTION_EH
            putExtra(GalleryActivity.KEY_GALLERY_INFO, gi)
        }
        startActivity(intent)
    }

    private fun openDetail(gi: GalleryInfo) {
        ehNavigator().push(EhRoute.GalleryDetail(gid = gi.gid, token = gi.token))
    }

    private fun downloadLabels(): List<String> {
        val labelRawList = DownloadManager.labelList
        val labels = ArrayList<String>(labelRawList.size + 1)
        labels.add(getString(R.string.default_download_label_name))
        labelRawList.forEach { info -> info.label?.let { labels.add(it) } }
        return labels
    }

    private fun moveLabel(gid: Long, index: Int) {
        val downloadInfo = DownloadManager.getDownloadInfo(gid) ?: return
        val labels = downloadLabels()
        if (index !in labels.indices) return
        // 下标 0 是默认标签，对应依赖里的 null
        DownloadManager.changeLabel(listOf(downloadInfo), if (index == 0) null else labels[index])
    }

    private fun deleteHistory(gi: GalleryInfo) {
        lifecycleScope.launchIO {
            val info = gi as? HistoryInfo ?: return@launchIO
            EhDB.deleteHistoryInfo(info)
        }
    }

    private fun clearHistory() {
        lifecycleScope.launchIO {
            EhDB.clearHistoryInfo()
            withUIContext { dataVersion++ }
        }
    }

    private class AddToFavoriteListener(context: Context) :
        EhCallback<GalleryListScene?, Unit>(context) {
        override fun onSuccess(result: Unit) {
            showTip(R.string.add_to_favorite_success, LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.add_to_favorite_failure, LENGTH_LONG)
        }

        override fun onCancel() {}
    }

    private class RemoveFromFavoriteListener(context: Context) :
        EhCallback<GalleryListScene?, Unit>(context) {
        override fun onSuccess(result: Unit) {
            showTip(R.string.remove_from_favorite_success, LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.remove_from_favorite_failure, LENGTH_LONG)
        }

        override fun onCancel() {}
    }
}
