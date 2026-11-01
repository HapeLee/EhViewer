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

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.EhApplication.Companion.galleryDetailCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.client.parser.ArchiveParser
import com.hippo.ehviewer.client.parser.HomeParser
import com.hippo.ehviewer.client.parser.RateGalleryParser
import com.hippo.ehviewer.client.parser.TorrentParser
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.readFromCache
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.screen.DetailPhase
import com.hippo.ehviewer.ui.compose.screen.TagUi
import com.hippo.ehviewer.ui.compose.state.GalleryCommentsStore
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG
import com.hippo.ehviewer.ui.host.TIP_LENGTH_SHORT
import com.hippo.util.ExceptionUtils
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import kotlinx.coroutines.launch

/**
 * 画廊详情页的数据装载与收藏 / 下载 / 投票状态。
 *
 * 出壳前这些住在 `GalleryDetailScene`（`SceneFragment`）里，`onSaveInstanceState` 存
 * `gid` / `token` / `page` / `GalleryInfo`。现在参数来自 [EhRoute.GalleryDetail]，
 * 详情优先命中 `galleryDetailCache`，未命中才请求。
 *
 * 刻意的老规矩：
 * - `archiveList` / `torrentList` / `currentFunds` 缓存在这里，对话框第二次打开不重新请求
 * - 收藏与评分仍然是「就地改 `GalleryDetail` + 自增 [version]」，由场景把版本号带进快照
 * - 请求回调仍按旧约定登记进 `application` 的全局表（旧 Intent 协议要按 id 找请求者），
 *   [onCleared] 时按 id 摘掉，避免表里留下死监听器
 */
class GalleryDetailViewModel(private val appContext: Context) : ViewModel() {

    var gid by mutableLongStateOf(-1L)
        private set
    var detail by mutableStateOf<GalleryDetail?>(null)
        private set
    var galleryInfo by mutableStateOf<GalleryInfo?>(null)
        private set

    var phase by mutableIntStateOf(DetailPhase.INIT)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var downloadState by mutableIntStateOf(DownloadInfo.STATE_INVALID)
        private set
    var startPage by mutableIntStateOf(-1)
        private set
    var localFavorites by mutableStateOf(false)
        private set
    var version by mutableIntStateOf(0)
        private set

    /** 一次性提示 */
    var tip by mutableStateOf<String?>(null)
        private set
    var tipLength by mutableIntStateOf(TIP_LENGTH_LONG)
        private set

    private var token: String? = null
    private var page = 0
    private var requestId = -1
    private var modifyingFavorites = false
    private var started = false

    private var archiveList: List<ArchiveParser.Archive>? = null
    private var currentFunds: HomeParser.Funds? = null
    private var torrentList: List<TorrentParser.Result>? = null

    val detailUrl: String?
        get() = if (gid == -1L) null else EhUrl.getGalleryDetailUrl(gid, token, page, false)

    val cachedArchives: List<ArchiveParser.Archive>?
        get() = archiveList

    val cachedFunds: HomeParser.Funds?
        get() = currentFunds

    val cachedTorrents: List<TorrentParser.Result>?
        get() = torrentList

    private val downloadInfoListener: DownloadInfoListener = object : DownloadInfoListener {
        override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) = updateDownloadState()
        override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>) = updateDownloadState()
        override fun onUpdateAll() = updateDownloadState()
        override fun onReload() = updateDownloadState()
        override fun onChange() = updateDownloadState()
        override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) = updateDownloadState()
        override fun onRenameLabel(from: String, to: String) {}
        override fun onUpdateLabels() {}
    }

    init {
        DownloadManager.addDownloadInfoListener(downloadInfoListener)
    }

    override fun onCleared() {
        DownloadManager.removeDownloadInfoListener(downloadInfoListener)
        if (requestId != -1) {
            application.removeGlobalStuff(requestId)
            requestId = -1
        }
    }

    fun start(route: EhRoute.GalleryDetail) {
        if (started) return
        started = true
        gid = route.gid
        token = route.token
        page = route.page
        if (prepareData()) {
            phase = when {
                detail != null -> DetailPhase.NORMAL
                galleryInfo != null -> DetailPhase.REFRESH_HEADER
                else -> DetailPhase.REFRESH
            }
        } else {
            errorText = appContext.getString(R.string.error_cannot_find_gallery)
            phase = DetailPhase.FAILED
        }
        updateDownloadState()
    }

    /** 回到前台：起点页 / 本地收藏 / 评论仓库各同步一次（原 `onResume`） */
    fun syncOnResume() {
        updateStartPage()
        updateLocalFavorites()
        syncCommentsFromStore()
    }

    fun consumeTip(): String? = tip.also { tip = null }

    fun retry() {
        phase = DetailPhase.REFRESH
        request()
    }

    fun refresh() {
        if (gid == -1L) return
        phase = if (detail != null) DetailPhase.REFRESH_HEADER else DetailPhase.REFRESH
        request()
    }

    // region 收藏 / 下载 / 评分 / 标签

    fun modifyFavorites(foreSelect: Boolean, activity: MainActivity) {
        val d = detail ?: return
        if (modifyingFavorites) return
        modifyingFavorites = true
        val listener = ModifyFavoritesListener(!localFavorites)
        if (localFavorites) {
            CommonOperations.removeFromFavorites(activity, d, listener, foreSelect)
        } else {
            CommonOperations.addToFavorites(activity, d, listener, foreSelect)
        }
        bumpVersion()
    }

    fun download(forceDefault: Boolean, activity: MainActivity) {
        val info = galleryInfo ?: detail ?: return
        CommonOperations.startDownload(activity, info, forceDefault)
    }

    fun deleteDownload(removeImages: Boolean) {
        val currentGid = gid
        Settings.putRemoveImageFiles(removeImages)
        // DownloadManager 的写操作是 suspend，用主线程起协程：DB 内部切 IO，
        // 内存态与监听器通知留在主线程（P6-c）
        viewModelScope.launch {
            DownloadManager.deleteDownload(currentGid)
            if (removeImages) {
                val file = SpiderDen.getGalleryDownloadDir(currentGid)
                DownloadManager.removeDownloadDirname(currentGid)
                launchIO { runCatching { file?.delete() } }
            }
        }
    }

    fun rate(rating: Float) {
        val d = detail ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_GET_RATE_GALLERY)
            .setArgs(d.apiUid, d.apiKey, d.gid, d.token, rating)
            .setCallback(
                object : EhClient.Callback<RateGalleryParser.Result> {
                    override fun onSuccess(result: RateGalleryParser.Result) {
                        detail?.let {
                            it.rating = result.rating
                            it.ratingCount = result.ratingCount
                        }
                        showTip(appContext.getString(R.string.rate_successfully), TIP_LENGTH_SHORT)
                        bumpVersion()
                    }

                    override fun onFailure(e: Exception) {
                        Log.e(TAG, "Rate gallery failed", e)
                        showTip(appContext.getString(R.string.rate_failed))
                    }

                    override fun onCancel() {}
                },
            )
            .enqueue(viewModelScope)
    }

    fun voteTag(tag: TagUi, vote: Int) {
        val d = detail ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_VOTE_TAG)
            .setArgs(d.apiUid, d.apiKey, d.gid, d.token, tag.tag, vote)
            .setCallback(
                object : EhClient.Callback<Pair<String, Array<GalleryTagGroup>?>> {
                    override fun onSuccess(result: Pair<String, Array<GalleryTagGroup>?>) {
                        if (result.first.isNotEmpty()) {
                            showTip(result.first, TIP_LENGTH_SHORT)
                        } else {
                            detail?.tags = result.second
                            showTip(appContext.getString(R.string.tag_vote_successfully), TIP_LENGTH_SHORT)
                            bumpVersion()
                        }
                    }

                    override fun onFailure(e: Exception) {
                        showTip(appContext.getString(R.string.vote_failed))
                    }

                    override fun onCancel() {}
                },
            )
            .enqueue(viewModelScope)
    }

    fun clearImageCache() {
        val currentGid = gid
        if (currentGid == -1L) return
        SpiderQueen.reset(currentGid)
        val set = detail?.previewSet
        val size = set?.size() ?: 0
        for (index in 0 until size) {
            com.hippo.ehviewer.EhApplication.imageCache.remove(
                com.hippo.ehviewer.client.getImageKey(currentGid, index),
            )
        }
        showTip(appContext.getString(R.string.action_image_cache_cleared), TIP_LENGTH_SHORT)
    }

    // endregion

    // region 归档 / 种子

    /**
     * 归档列表：已缓存的直接回调，否则请求。
     *
     * 结果的展示与「下载归档」的落地（Intent / 系统下载器）留在场景里——那部分要 Activity。
     */
    fun loadArchives(onSuccess: (List<ArchiveParser.Archive>, HomeParser.Funds?) -> Unit, onFailure: (Exception) -> Unit) {
        val d = detail ?: return
        val cached = archiveList
        if (cached != null) {
            onSuccess(cached, currentFunds)
            return
        }
        val url = d.archiveUrl ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_ARCHIVE_LIST)
            .setArgs(url, gid, token)
            .setCallback(
                object : EhClient.Callback<ArchiveParser.Result> {
                    override fun onSuccess(result: ArchiveParser.Result) {
                        archiveList = result.archiveList
                        currentFunds = result.funds
                        onSuccess(result.archiveList.orEmpty(), result.funds)
                    }

                    override fun onFailure(e: Exception) = onFailure(e)

                    override fun onCancel() {}
                },
            )
            .enqueue(viewModelScope)
    }

    fun downloadArchive(
        archive: ArchiveParser.Archive,
        onSuccess: (String?) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val d = detail ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_DOWNLOAD_ARCHIVE)
            .setArgs(d.gid, d.token, archive.res, archive.isHAtH)
            .setCallback(
                object : EhClient.Callback<String?> {
                    override fun onSuccess(result: String?) = onSuccess(result)

                    override fun onFailure(e: Exception) {
                        Log.e(TAG, "Error", e)
                        onFailure(e)
                    }

                    override fun onCancel() {}
                },
            )
            .enqueue(viewModelScope)
    }

    fun loadTorrents(onSuccess: (List<TorrentParser.Result>) -> Unit, onFailure: (Exception) -> Unit) {
        val d = detail ?: return
        val cached = torrentList
        if (cached != null) {
            onSuccess(cached)
            return
        }
        val url = d.torrentUrl ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_GET_TORRENT_LIST)
            .setArgs(url, gid, token)
            .setCallback(
                object : EhClient.Callback<List<TorrentParser.Result>> {
                    override fun onSuccess(result: List<TorrentParser.Result>) {
                        torrentList = result
                        onSuccess(result)
                    }

                    override fun onFailure(e: Exception) = onFailure(e)

                    override fun onCancel() {}
                },
            )
            .enqueue(viewModelScope)
    }

    // endregion

    // region 数据装载

    private fun prepareData(): Boolean {
        if (detail != null) return true
        if (gid == -1L) return false
        galleryDetailCache.get(gid)?.let {
            detail = it
            galleryInfo = it
        }
        if (detail != null) return true
        if (requestId != -1 && application.containGlobalStuff(requestId)) return true
        request()
        return true
    }

    private fun request() {
        val url = detailUrl ?: return
        val listener = GetGalleryDetailListener()
        requestId = application.putGlobalStuff(listener)
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(url)
            .setCallback(listener)
            .enqueue(viewModelScope)
    }

    private fun updateStartPage() {
        val currentGid = gid
        if (currentGid == -1L || downloadState != DownloadInfo.STATE_FINISH) return
        viewModelScope.launchIO {
            val page = readFromCache(currentGid)?.startPage ?: 0
            withUIContext { startPage = page }
        }
    }

    private fun updateDownloadState() {
        val currentGid = gid
        if (currentGid == -1L) return
        val state = DownloadManager.getDownloadState(currentGid)
        if (state == downloadState) return
        downloadState = state
        updateStartPage()
    }

    private fun updateLocalFavorites() {
        val currentGid = gid
        if (currentGid == -1L) return
        viewModelScope.launchIO {
            val contained = EhDB.containLocalFavorites(currentGid)
            withUIContext { localFavorites = contained }
        }
    }

    /**
     * 评论页改完评论后把结果写进 [GalleryCommentsStore]，这里回到前台时读一次。
     *
     * 取代旧的 `onSceneResult(REQUEST_CODE_COMMENT_GALLERY)`：返回时详情页可能已经被
     * 回收重建，按 tag 找请求方的旧机制会静默丢更新；仓库读法不依赖任何一方存活。
     */
    private fun syncCommentsFromStore() {
        val currentGid = gid
        if (currentGid == -1L) return
        val stored = GalleryCommentsStore.latest(currentGid) ?: return
        val target = detail ?: return
        if (target.comments === stored) return
        target.comments = stored
        bumpVersion()
    }

    // endregion

    private fun bumpVersion() {
        version++
    }

    private fun showTip(message: String, length: Int = TIP_LENGTH_LONG) {
        tip = message
        tipLength = length
    }

    private inner class GetGalleryDetailListener : EhClient.Callback<GalleryDetail> {
        override fun onSuccess(result: GalleryDetail) {
            application.removeGlobalStuff(this)
            requestId = -1
            galleryDetailCache.put(result.gid, result)
            EhDB.putHistoryInfo(result)
            detail = result
            galleryInfo = result
            gid = result.gid
            token = result.token
            errorText = null
            updateDownloadState()
            phase = DetailPhase.NORMAL
            bumpVersion()
        }

        override fun onFailure(e: Exception) {
            application.removeGlobalStuff(this)
            requestId = -1
            Log.e(TAG, "Load gallery detail failed", e)
            errorText = ExceptionUtils.getReadableString(e)
            phase = DetailPhase.FAILED
        }

        override fun onCancel() {
            application.removeGlobalStuff(this)
            requestId = -1
        }
    }

    private inner class ModifyFavoritesListener(private val remove: Boolean) : EhClient.Callback<Unit> {
        override fun onSuccess(result: Unit) {
            modifyingFavorites = false
            updateLocalFavorites()
            detail?.isFavorited = !remove && detail?.favoriteName != null
            showTip(
                appContext.getString(
                    if (remove) R.string.remove_from_favorite_success else R.string.add_to_favorite_success,
                ),
                TIP_LENGTH_SHORT,
            )
            bumpVersion()
        }

        override fun onFailure(e: Exception) {
            modifyingFavorites = false
            showTip(
                appContext.getString(
                    if (remove) R.string.remove_from_favorite_failure else R.string.add_to_favorite_failure,
                ),
            )
        }

        override fun onCancel() {
            modifyingFavorites = false
        }
    }

    private companion object {
        const val TAG = "GalleryDetailViewModel"
    }
}
