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
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.DownloadItemUi
import com.hippo.ehviewer.ui.compose.screen.DownloadLabelUi
import com.hippo.ehviewer.ui.compose.screen.DownloadsScreen
import com.hippo.ehviewer.ui.compose.screen.DownloadsUiState
import com.hippo.ehviewer.ui.host.ehHostOrNoOp
import com.hippo.unifile.UniFile
import com.hippo.util.launchIO
import com.hippo.util.launchNonCancellable
import com.hippo.yorozuya.collect.LongList

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

private fun downloadComparator(sort: Int): Comparator<DownloadInfo> = Comparator { o1, o2 ->
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

/**
 * 下载页。
 *
 * P7-c 出壳：不再是 Fragment，`onCreate` / `onDestroy` 的监听注册换成 [DisposableEffect]，
 * `onSaveInstanceState` 的标签记忆换成 `rememberSaveable`，`onBackPressed` 的多选取消
 * 换成 [BackHandler]，`onNewArguments` 的参数换新由 route 承担
 * （`EhNavController.push` 按 [com.hippo.ehviewer.ui.compose.nav.navSlot] 换掉同一页的参数）。
 *
 * 列表派生（过滤 / 排序）、多选集合、标签操作的语义与旧实现一致。
 */
@Composable
fun DownloadsScene(route: EhRoute.Downloads) {
    val context = LocalContext.current
    // 起服务、读画廊都要宿主 Activity
    val activity = context as? MainActivity
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val scope = rememberCoroutineScope()

    val defaultLabelName = stringResource(R.string.default_download_label_name)
    val allLabelName = stringResource(R.string.download_all)

    // 通知栏带 gid 进来：落到那一项所在的标签，并滚过去
    val initialLabel = remember {
        val info = if (route.gid != -1L) DownloadManager.getDownloadInfo(route.gid) else null
        if (info != null) info.label else Settings.recentDownloadLabel
    }

    var label by rememberSaveable { mutableStateOf(initialLabel) }
    var sort by remember { mutableIntStateOf(Settings.defaultSortingMethod) }
    var typeFilter by remember { mutableIntStateOf(-1) }
    var keyword by remember { mutableStateOf<String?>(null) }
    var infos by remember { mutableStateOf<List<DownloadInfo>>(emptyList()) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var version by remember { mutableIntStateOf(0) }
    var scrollToGid by remember { mutableStateOf(route.gid.takeIf { it != -1L }) }

    fun rebuild() {
        var raw: List<DownloadInfo> = when {
            label == null -> DownloadManager.allDownloadInfoList
            label == defaultLabelName -> DownloadManager.defaultDownloadInfoList
            else -> DownloadManager.getLabelDownloadInfoList(label!!) ?: run {
                label = null
                DownloadManager.allDownloadInfoList
            }
        }
        // 与旧实现一致的 or 语义：关键字过滤与状态过滤共用同一个槽位
        if (typeFilter != -1) {
            val currentKeyword = keyword
            raw = raw.filter {
                currentKeyword != null && EhUtils.getSuitableTitle(it).contains(currentKeyword, true) ||
                    it.state == typeFilter
            }
        }
        infos = if (sort == 10) raw.shuffled() else raw.sortedWith(downloadComparator(sort))
        version++
        Settings.putRecentDownloadLabel(label)
    }

    val listener = remember {
        object : DownloadInfoListener {
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
    }

    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialized) {
            initialized = true
            if (route.clearService) {
                DownloadService.clear()
            }
        }
        rebuild()
    }

    DisposableEffect(listener) {
        DownloadManager.addDownloadInfoListener(listener)
        onDispose { DownloadManager.removeDownloadInfoListener(listener) }
    }

    // 多选状态下返回键先退出多选，不弹页面
    BackHandler(enabled = selection.isNotEmpty()) {
        selection = emptySet()
    }

    fun selectedInfos(): List<DownloadInfo> = infos.filter { it.gid in selection }

    fun pinTopSelected() {
        val pinList = selectedInfos().reversed()
        val now = System.currentTimeMillis()
        pinList.forEachIndexed { index, info ->
            info.time = now + index
            EhDB.putDownloadInfo(info)
        }
        selection = emptySet()
        rebuild()
    }

    fun startSelected() {
        val gids = LongList()
        selectedInfos().forEach { gids.add(it.gid) }
        if (gids.isEmpty()) return
        val target = activity ?: return
        val intent = Intent(target, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_RANGE
        intent.putExtra(DownloadService.KEY_GID_LIST, gids)
        ContextCompat.startForegroundService(target, intent)
        selection = emptySet()
    }

    fun stopSelected() {
        val gids = LongList()
        selectedInfos().forEach { gids.add(it.gid) }
        if (gids.isEmpty()) return
        DownloadManager.stopRangeDownload(gids)
        selection = emptySet()
    }

    fun deleteSelected(removeFiles: Boolean) {
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
            scope.launchIO {
                runCatching { files.forEach { it?.delete() } }
            }
        }
    }

    fun moveSelected(newLabel: String?) {
        val list = selectedInfos()
        if (list.isEmpty()) return
        selection = emptySet()
        DownloadManager.changeLabel(list, newLabel)
        rebuild()
    }

    fun startOne(gid: Long) {
        val info = infos.firstOrNull { it.gid == gid } ?: return
        val target = activity ?: return
        val intent = Intent(target, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START
        intent.putExtra(DownloadService.KEY_GALLERY_INFO, info)
        ContextCompat.startForegroundService(target, intent)
    }

    fun startAll() {
        val target = activity ?: return
        val intent = Intent(target, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_ALL
        ContextCompat.startForegroundService(target, intent)
    }

    fun startAllReversed() {
        val target = activity ?: return
        val gids = LongList()
        for (index in infos.indices.reversed()) {
            val info = infos[index]
            if (info.state != DownloadInfo.STATE_FINISH) {
                gids.add(info.gid)
            }
        }
        if (gids.isEmpty()) return
        val intent = Intent(target, DownloadService::class.java)
        intent.action = DownloadService.ACTION_START_RANGE
        intent.putExtra(DownloadService.KEY_GID_LIST, gids)
        ContextCompat.startForegroundService(target, intent)
    }

    fun confirmResetReadingProgress() {
        AlertDialog.Builder(context)
            .setMessage(R.string.reset_reading_progress_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                scope.launchNonCancellable {
                    DownloadManager.resetAllReadingProgress()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun readDownloaded(gid: Long) {
        val info = infos.firstOrNull { it.gid == gid } ?: return
        context.startActivity(
            Intent(context, GalleryActivity::class.java).apply {
                action = GalleryActivity.ACTION_EH
                putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
            },
        )
    }

    fun openDetail(gid: Long) {
        val info = infos.firstOrNull { it.gid == gid } ?: return
        navigator.push(EhRoute.GalleryDetail(gid = info.gid, token = info.token))
    }

    fun validateLabel(name: String, self: String?): Boolean {
        if (name == allLabelName || name == defaultLabelName) {
            host.showTip(R.string.label_text_is_invalid, BaseScene.LENGTH_LONG)
            return false
        }
        if (name != self && DownloadManager.containLabel(name)) {
            host.showTip(R.string.label_text_exist, BaseScene.LENGTH_LONG)
            return false
        }
        return true
    }

    fun selectLabel(name: String?) {
        if (label == name) return
        label = name
        selection = emptySet()
        rebuild()
    }

    fun addLabel(name: String) {
        if (!validateLabel(name, null)) return
        DownloadManager.addLabel(name)
        rebuild()
    }

    fun renameLabel(from: String, to: String) {
        if (from == to) return
        if (!validateLabel(to, from)) return
        DownloadManager.renameLabel(from, to)
        if (label == from) label = to
        rebuild()
    }

    fun deleteLabel(name: String) {
        DownloadManager.deleteLabel(name)
        if (label == name) label = null
        rebuild()
    }

    fun moveLabel(from: Int, to: Int) {
        // 前两项是「全部」「默认」，真实标签从 0 开始
        val realFrom = from - LABEL_OFFSET
        val realTo = to - LABEL_OFFSET
        if (realFrom < 0 || realTo < 0 || realFrom == realTo) return
        DownloadManager.moveLabel(realFrom, realTo)
        rebuild()
    }

    fun setDefaultLabel(hasDefault: Boolean, name: String?) {
        Settings.putHasDefaultDownloadLabel(hasDefault)
        if (hasDefault) {
            Settings.putDefaultDownloadLabel(name)
        }
        rebuild()
    }

    fun buildLabels(): List<DownloadLabelUi> {
        val result = ArrayList<DownloadLabelUi>()
        result += DownloadLabelUi(
            name = null,
            display = allLabelName,
            count = DownloadManager.allDownloadInfoList.size,
            selected = label == null,
            draggable = false,
        )
        result += DownloadLabelUi(
            name = defaultLabelName,
            display = defaultLabelName,
            count = DownloadManager.defaultDownloadInfoList.size,
            selected = label == defaultLabelName,
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

    DownloadsScreen(
        state = DownloadsUiState(
            title = context.getString(R.string.scene_download_title, label ?: allLabelName),
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
        ),
        onOpenNavDrawer = { host.toggleDrawer(GravityCompat.START) },
        onToggleSelect = { gid -> selection = if (gid in selection) selection - gid else selection + gid },
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
