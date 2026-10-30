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
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.download.DownloadService.Companion.clear
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.DownloadItemUi
import com.hippo.ehviewer.ui.compose.screen.DownloadLabelUi
import com.hippo.ehviewer.ui.compose.screen.DownloadsScreen
import com.hippo.ehviewer.ui.compose.screen.DownloadsUiState
import com.hippo.unifile.UniFile
import com.hippo.util.getParcelableCompat
import com.hippo.util.launchIO
import com.hippo.util.launchNonCancellable
import com.hippo.yorozuya.collect.LongList

/**
 * 下载页。
 *
 * 结构调整：
 * - 界面整体搬到 Compose（[DownloadsScreen]），本类只保留列表派生（过滤/排序）、多选集合和下载管理交互
 * - 7 个 FAB 的操作台改成多选顶栏；右侧标签抽屉搬进场景内的 `ModalNavigationDrawer`
 * - 列表从 `AutoStaggeredGridLayoutManager` 换成自适应列宽的 `LazyVerticalGrid`，
 *   不再提供 FastScroller 与「手动拖动置顶」，置顶改由多选菜单触发（按 time 写库，语义与旧实现一致）
 * - 数据仍是 `DownloadManager` 的内存表，但每次变化都重建一份不可变快照 [infos]，
 *   避免 Compose 读到可变对象内部字段后不刷新
 */
class DownloadsScene : EhComposeScene() {

    private var label: String? = null
    private var sort = Settings.defaultSortingMethod
    private var typeFilter = -1
    private var keyword: String? = null
    private var infos: List<DownloadInfo> = emptyList()
    private var selection by mutableStateOf(emptySet<Long>())
    private var version by mutableStateOf(0)
    private var scrollToGid by mutableStateOf<Long?>(null)

    private val listener = object : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) = rebuild()
        override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>) {
            version++
        }
        override fun onUpdateAll() = rebuild()
        override fun onReload() = rebuild()
        override fun onChange() {
            label = null
            rebuild()
        }
        override fun onRenameLabel(from: String, to: String) {
            if (label == from) label = to
            rebuild()
        }
        override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) = rebuild()
        override fun onUpdateLabels() = rebuild()
    }

    override fun getNavCheckedItem(): Int = R.id.nav_downloads

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.addDownloadInfoListener(listener)
        if (savedInstanceState == null) {
            if (!handleArguments(arguments)) {
                label = Settings.recentDownloadLabel
            }
        } else {
            label = savedInstanceState.getString(KEY_LABEL)
        }
        rebuild()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LABEL, label)
    }

    override fun onNewArguments(args: Bundle) {
        if (handleArguments(args)) {
            rebuild()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadManager.removeDownloadInfoListener(listener)
    }

    override fun onBackPressed() {
        if (selection.isNotEmpty()) {
            selection = emptySet()
            return
        }
        super.onBackPressed()
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.Downloads> {
            DownloadsScreen(
                state = uiState(),
                onOpenNavDrawer = { toggleDrawer(GravityCompat.START) },
                onToggleSelect = ::toggleSelect,
                onSelectAll = { selection = infos.map { it.gid }.toSet() },
                onClearSelection = { selection = emptySet() },
                onRead = ::readDownloaded,
                onOpenDetail = ::openDetail,
                onStartOne = ::startOne,
                onStopOne = { DownloadManager.stopDownload(it) },
                onPinTopSelected = ::pinTopSelected,
                onStartSelected = ::startSelected,
                onStopSelected = ::stopSelected,
                onDeleteSelected = ::deleteSelected,
                onMoveSelected = ::moveSelected,
                onFilterType = {
                    typeFilter = it
                    keyword = null
                    rebuild()
                },
                onFilterTitle = {
                    typeFilter = FILTER_TYPE_TITLE
                    keyword = it.lowercase()
                    rebuild()
                },
                onSort = {
                    sort = it
                    Settings.putDefaultSortingMethod(it)
                    rebuild()
                },
                onStartAll = ::startAll,
                onStopAll = { DownloadManager.stopAllDownload() },
                onStartAllReversed = ::startAllReversed,
                onResetReadingProgress = ::confirmResetReadingProgress,
                onSelectLabel = ::selectLabel,
                onAddLabel = ::addLabel,
                onRenameLabel = ::renameLabel,
                onDeleteLabel = ::deleteLabel,
                onMoveLabel = ::moveLabel,
                onSetDefaultLabel = ::setDefaultLabel,
                onScrollToGidDone = { scrollToGid = null },
            )
        }
    }

    private fun uiState(): DownloadsUiState = DownloadsUiState(
        title = getString(
            R.string.scene_download_title,
            label ?: getString(R.string.download_all),
        ),
        items = infos.map { info ->
            DownloadItemUi(
                gid = info.gid,
                title = EhUtils.getSuitableTitle(info),
                uploader = info.uploader,
                rating = info.rating,
                category = info.category,
                thumbUrl = info.thumb?.thumbUrl,
                state = info.state,
                legacy = info.legacy,
                finished = info.finished,
                total = info.total,
                speed = info.speed,
                label = info.label,
                selected = info.gid in selection,
            )
        },
        labels = buildLabels(),
        selectionMode = selection.isNotEmpty(),
        selectedCount = selection.size,
        sort = sort,
        typeFilter = typeFilter,
        keyword = keyword,
        scrollToGid = scrollToGid,
        version = version,
    )

    private fun buildLabels(): List<DownloadLabelUi> {
        val defaultName = getString(R.string.default_download_label_name)
        val result = ArrayList<DownloadLabelUi>()
        result += DownloadLabelUi(
            name = null,
            display = getString(R.string.download_all),
            count = DownloadManager.allDownloadInfoList.size,
            selected = label == null,
            draggable = false,
        )
        result += DownloadLabelUi(
            name = defaultName,
            display = defaultName,
            count = DownloadManager.defaultDownloadInfoList.size,
            selected = label == defaultName,
            draggable = false,
        )
        DownloadManager.labelList.forEach { downloadLabel ->
            val name = downloadLabel.label ?: return@forEach
            result += DownloadLabelUi(
                name = name,
                display = name,
                count = DownloadManager.getLabelDownloadInfoList(name)?.size ?: 0,
                selected = label == name,
                draggable = true,
            )
        }
        return result
    }

    // region 列表派生

    private fun rebuild() {
        val defaultName = getString(R.string.default_download_label_name)
        var raw: List<DownloadInfo> = when {
            label == null -> DownloadManager.allDownloadInfoList
            label == defaultName -> DownloadManager.defaultDownloadInfoList
            else -> DownloadManager.getLabelDownloadInfoList(label) ?: run {
                label = null
                DownloadManager.allDownloadInfoList
            }
        }
        // 与旧实现一致的 or 语义：关键字过滤与状态过滤共用同一个 mType 槽位
        if (typeFilter != -1) {
            val currentKeyword = keyword
            raw = raw.filter {
                currentKeyword != null && EhUtils.getSuitableTitle(it).contains(currentKeyword, true) ||
                    it.state == typeFilter
            }
        }
        infos = if (sort == 10) {
            raw.shuffled()
        } else {
            raw.sortedWith(comparator)
        }
        version++
        Settings.putRecentDownloadLabel(label)
    }

    private val comparator: Comparator<DownloadInfo> = Comparator { o1, o2 ->
        val title1 = EhUtils.getSuitableTitle(o1)
        val title2 = EhUtils.getSuitableTitle(o2)
        when (sort) {
            0 -> o2.time.compareTo(o1.time)
            1 -> o1.time.compareTo(o2.time)
            2 -> title1.compareTo(title2, true)
            3 -> title2.compareTo(title1, true)
            4 -> getAuthor(title1).compareTo(getAuthor(title2), true)
            5 -> getAuthor(title2).compareTo(getAuthor(title1), true)
            6 -> getName(title1).compareTo(getName(title2), true)
            7 -> getName(title2).compareTo(getName(title1), true)
            8 -> o1.category.compareTo(o2.category)
            9 -> o2.category.compareTo(o1.category)
            else -> 0
        }
    }

    private fun handleArguments(args: Bundle?): Boolean {
        args ?: return false
        if (ACTION_CLEAR_DOWNLOAD_SERVICE == args.getString(KEY_ACTION)) {
            clear()
        }
        val gid = args.getLong(KEY_GID, -1L)
        if (gid != -1L) {
            val info = DownloadManager.getDownloadInfo(gid) ?: return false
            label = info.label
            rebuild()
            scrollToGid = gid
            return true
        }
        return false
    }

    // endregion

    // region 多选与操作

    private fun toggleSelect(gid: Long) {
        selection = if (gid in selection) selection - gid else selection + gid
    }

    private fun selectedInfos(): List<DownloadInfo> = infos.filter { it.gid in selection }

    private fun pinTopSelected() {
        val pinList = selectedInfos().reversed()
        val now = System.currentTimeMillis()
        pinList.forEachIndexed { index, info ->
            info.time = now + index
            EhDB.putDownloadInfo(info)
        }
        selection = emptySet()
        rebuild()
    }

    private fun startSelected() {
        val gids = LongList()
        selectedInfos().forEach { gids.add(it.gid) }
        if (gids.isEmpty()) return
        val activity = mainActivity ?: return
        val intent = Intent(activity, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_RANGE
        intent.putExtra(DownloadService.KEY_GID_LIST, gids)
        ContextCompat.startForegroundService(activity, intent)
        selection = emptySet()
    }

    private fun stopSelected() {
        val gids = LongList()
        selectedInfos().forEach { gids.add(it.gid) }
        if (gids.isEmpty()) return
        DownloadManager.stopRangeDownload(gids)
        selection = emptySet()
    }

    private fun deleteSelected(removeFiles: Boolean) {
        val list = selectedInfos()
        if (list.isEmpty()) return
        val gids = LongList()
        list.forEach { gids.add(it.gid) }
        selection = emptySet()
        DownloadManager.deleteRangeDownload(gids)
        Settings.putRemoveImageFiles(removeFiles)
        if (removeFiles) {
            val files = arrayOfNulls<UniFile>(list.size)
            for ((index, info) in list.withIndex()) {
                files[index] = SpiderDen.getGalleryDownloadDir(info.gid)
                DownloadManager.removeDownloadDirname(info.gid)
            }
            lifecycleScope.launchIO {
                runCatching { files.forEach { it?.delete() } }
            }
        }
    }

    private fun moveSelected(newLabel: String?) {
        val list = selectedInfos()
        if (list.isEmpty()) return
        selection = emptySet()
        DownloadManager.changeLabel(list, newLabel)
        rebuild()
    }

    private fun startOne(gid: Long) {
        val info = infos.firstOrNull { it.gid == gid } ?: return
        val activity = mainActivity ?: return
        val intent = Intent(activity, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START
        intent.putExtra(DownloadService.KEY_GALLERY_INFO, info)
        ContextCompat.startForegroundService(activity, intent)
    }

    private fun startAll() {
        val activity = mainActivity ?: return
        val intent = Intent(activity, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_ALL
        ContextCompat.startForegroundService(activity, intent)
    }

    private fun startAllReversed() {
        val activity = mainActivity ?: return
        val gids = LongList()
        for (index in infos.indices.reversed()) {
            val info = infos[index]
            if (info.state != DownloadInfo.STATE_FINISH) {
                gids.add(info.gid)
            }
        }
        if (gids.isEmpty()) return
        val intent = Intent(activity, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_RANGE
        intent.putExtra(DownloadService.KEY_GID_LIST, gids)
        ContextCompat.startForegroundService(activity, intent)
    }

    private fun confirmResetReadingProgress() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setMessage(R.string.reset_reading_progress_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launchNonCancellable {
                    DownloadManager.resetAllReadingProgress()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun readDownloaded(gid: Long) {
        val info = infos.firstOrNull { it.gid == gid } ?: return
        val activity = mainActivity ?: return
        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_EH
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
        startActivity(intent)
    }

    private fun openDetail(gid: Long) {
        val info = infos.firstOrNull { it.gid == gid } ?: return
        ehNavigator().push(EhRoute.GalleryDetail(gid = info.gid, token = info.token))
    }

    // endregion

    // region 标签

    private fun selectLabel(name: String?) {
        if (label == name) return
        label = name
        selection = emptySet()
        rebuild()
    }

    private fun addLabel(name: String) {
        if (!validateLabel(name, null)) return
        DownloadManager.addLabel(name)
        rebuild()
    }

    private fun renameLabel(from: String, to: String) {
        if (from == to) return
        if (!validateLabel(to, from)) return
        DownloadManager.renameLabel(from, to)
        if (label == from) label = to
        rebuild()
    }

    private fun deleteLabel(name: String) {
        DownloadManager.deleteLabel(name)
        if (label == name) label = null
        rebuild()
    }

    private fun moveLabel(from: Int, to: Int) {
        // 前两项是「全部」「默认」，真实标签从 0 开始
        val realFrom = from - LABEL_OFFSET
        val realTo = to - LABEL_OFFSET
        if (realFrom < 0 || realTo < 0 || realFrom == realTo) return
        DownloadManager.moveLabel(realFrom, realTo)
        rebuild()
    }

    private fun setDefaultLabel(hasDefault: Boolean, name: String?) {
        Settings.putHasDefaultDownloadLabel(hasDefault)
        if (hasDefault) {
            Settings.putDefaultDownloadLabel(name)
        }
        rebuild()
    }

    private fun validateLabel(name: String, self: String?): Boolean {
        val defaultName = getString(R.string.default_download_label_name)
        val allName = getString(R.string.download_all)
        if (name == allName || name == defaultName) {
            showTip(R.string.label_text_is_invalid, LENGTH_LONG)
            return false
        }
        if (name != self && DownloadManager.containLabel(name)) {
            showTip(R.string.label_text_exist, LENGTH_LONG)
            return false
        }
        return true
    }

    // endregion

    companion object {
        const val KEY_GID = "gid"
        const val KEY_ACTION = "action"
        const val ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service"

        private const val KEY_LABEL = "label"
        private const val LABEL_OFFSET = 2

        /** 标题关键字过滤借用 `mType` 槽位，取值与任何真实下载状态都不冲突 */
        private const val FILTER_TYPE_TITLE = 5

        private val PATTERN_AUTHOR = Regex("^(?:\\([^\\[\\]()]+\\))?\\s*\\[([^\\[\\]]+)]")
        private val PATTERN_NAME = Regex("^(?:\\([^\\[\\]()]+\\))?\\s*(?:\\[[^\\[\\]]+])?\\s*(.+)")

        private fun getAuthor(title: String): String {
            val matcher = PATTERN_AUTHOR.find(title) ?: return ""
            return matcher.groupValues[1].trim { it <= ' ' }
        }

        private fun getName(title: String): String {
            val matcher = PATTERN_NAME.find(title) ?: return title
            return matcher.groupValues[1].trim { it <= ' ' }
        }
    }
}
