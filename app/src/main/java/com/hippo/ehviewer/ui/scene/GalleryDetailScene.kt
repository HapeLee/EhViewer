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

import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.app.CheckBoxDialogBuilder
import com.hippo.ehviewer.EhApplication.Companion.application
import com.hippo.ehviewer.EhApplication.Companion.galleryDetailCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryComment
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.PreviewSet
import com.hippo.ehviewer.client.parser.ArchiveParser
import com.hippo.ehviewer.client.parser.HomeParser
import com.hippo.ehviewer.client.parser.RateGalleryParser
import com.hippo.ehviewer.client.parser.TorrentParser
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadManager.DownloadInfoListener
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.readFromCache
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.screen.CommentUi
import com.hippo.ehviewer.ui.compose.screen.DetailPhase
import com.hippo.ehviewer.ui.compose.screen.GalleryDetailScreen
import com.hippo.ehviewer.ui.compose.screen.GalleryDetailUiState
import com.hippo.ehviewer.ui.compose.screen.PreviewUi
import com.hippo.ehviewer.ui.compose.screen.TagGroupUi
import com.hippo.ehviewer.ui.compose.screen.TagStatus
import com.hippo.ehviewer.ui.compose.screen.TagUi
import com.hippo.ehviewer.ui.compose.state.GalleryCommentsStore
import com.hippo.ehviewer.ui.compose.text.toAnnotatedString
import com.hippo.util.AppHelper
import com.hippo.util.ExceptionUtils
import com.hippo.util.ReadableTime
import com.hippo.util.addTextToClipboard
import com.hippo.util.getParcelableCompat
import com.hippo.util.launchIO
import com.hippo.util.loadHtml
import com.hippo.util.withUIContext
import kotlin.math.roundToInt
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 画廊详情页。
 *
 * 结构调整：
 * - 界面整体搬到 Compose（[GalleryDetailScreen]），本类只保留数据装载、收藏/下载状态、标签投票和导航
 * - `ViewTransition` 五态改成 [DetailPhase] 单值状态；头部 CircularReveal 与共享元素转场不再保留
 * - 标签 / 评论 / 预览不再逐 View inflate，而是每次状态变化重建不可变快照（[TagGroupUi] / [CommentUi] / [PreviewUi]）
 * - 归档 / 种子 / 评分对话框仍用原有 XML 布局 + `DialogHelper` 式实现，只是从 Compose 页面里触发
 */
class GalleryDetailScene : EhComposeScene() {

    private var galleryInfo: GalleryInfo? = null
    private var detail: GalleryDetail? = null
    private var gid = -1L
    private var token: String? = null
    private var page = 0
    private var requestId = -1

    private var phase by mutableStateOf(DetailPhase.INIT)
    private var errorText by mutableStateOf<String?>(null)
    private var downloadState by mutableStateOf(DownloadInfo.STATE_INVALID)
    private var modifyingFavorites = false
    private var startPage by mutableStateOf(-1)

    /** 本地收藏是 DB 查询，不能放进 `uiState()` 里跟随重组反复查 */
    private var localFavorites by mutableStateOf(false)
    private var version by mutableStateOf(0)
    private var archiveList: List<ArchiveParser.Archive>? = null
    private var currentFunds: HomeParser.Funds? = null
    private var torrentList: List<TorrentParser.Result>? = null

    private val galleryDetailUrl: String?
        get() = if (gid == -1L) null else EhUrl.getGalleryDetailUrl(gid, token, page, false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.addDownloadInfoListener(downloadInfoListener)
        if (savedInstanceState == null) {
            handleArgs(arguments)
        } else {
            gid = savedInstanceState.getLong(KEY_GID, -1L)
            token = savedInstanceState.getString(KEY_TOKEN)
            page = savedInstanceState.getInt(KEY_PAGE, 0)
            galleryInfo = savedInstanceState.getParcelableCompat(KEY_GALLERY_INFO)
        }
        if (prepareData()) {
            phase = when {
                detail != null -> DetailPhase.NORMAL
                galleryInfo != null -> DetailPhase.REFRESH_HEADER
                else -> DetailPhase.REFRESH
            }
        } else {
            errorText = getString(R.string.error_cannot_find_gallery)
            phase = DetailPhase.FAILED
        }
        updateDownloadState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_GID, gid)
        outState.putString(KEY_TOKEN, token)
        outState.putInt(KEY_PAGE, page)
        outState.putParcelable(KEY_GALLERY_INFO, galleryInfo ?: detail)
    }

    override fun onResume() {
        super.onResume()
        updateStartPage()
        updateLocalFavorites()
        syncCommentsFromStore()
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
        val current = detail?.comments
        if (current === stored) return
        val target = detail ?: return
        target.comments = stored
        bumpVersion()
    }

    private fun updateLocalFavorites() {
        val currentGid = gid
        if (currentGid == -1L) return
        lifecycleScope.launchIO {
            val contained = EhDB.containLocalFavorites(currentGid)
            withUIContext { localFavorites = contained }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadManager.removeDownloadInfoListener(downloadInfoListener)
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        galleryDetailUrl?.let { outContent.webUri = it.toUri() }
    }

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.GalleryDetail> {
            GalleryDetailScreen(
                state = uiState(),
                onBack = ::onBackPressed,
                onRetry = ::retry,
                onRefresh = ::refresh,
                onAddTag = ::showAddTagDialog,
                onClearImageCache = ::clearImageCache,
                onOpenInOtherApp = ::openInOtherApp,
                onUploaderClick = ::openUploader,
                onUploaderLongClick = ::showFilterUploaderDialog,
                onCategoryClick = ::openCategory,
                onDownloadClick = ::onDownloadClick,
                onDownloadLongClick = ::downloadToDefaultDir,
                onReadClick = ::read,
                onInfoClick = ::showGalleryInfo,
                onNewerVersionClick = ::showNewerVersions,
                onFavoriteClick = { modifyFavorites(false) },
                onFavoriteLongClick = { modifyFavorites(true) },
                onShareClick = ::share,
                onTorrentClick = ::showTorrentList,
                onArchiveClick = ::showArchiveList,
                onSimilarClick = ::showSimilarGalleryList,
                onRateClick = ::showRateDialog,
                onTagClick = ::openTagList,
                onVoteTag = ::voteTag,
                onFilterTag = ::addTagFilter,
                onCopyTag = { requireActivity().addTextToClipboard(it, false) },
                onShowTagDefinition = { tag ->
                    context?.let { UrlOpener.openUrl(it, EhUrl.getTagDefinitionUrl(tagPlainName(tag)), false) }
                },
                onCommentsClick = ::showComments,
                onCommentClick = { showComments() },
                onPreviewClick = ::openPreviewPage,
                onPreviewsMoreClick = ::showPreviews,
            )
        }
    }

    // region 状态快照

    private fun uiState(): GalleryDetailUiState {
        val d = detail
        val info: GalleryInfo? = d ?: galleryInfo
        return GalleryDetailUiState(
            phase = phase,
            error = errorText,
            gid = gid,
            title = info?.let { EhUtils.getSuitableTitle(it) }.orEmpty(),
            uploader = info?.uploader,
            disowned = info?.disowned ?: false,
            category = info?.category ?: EhUtils.UNKNOWN,
            thumbUrl = info?.thumbUrl,
            language = d?.language,
            pages = info?.pages ?: 0,
            size = d?.size?.toString(),
            posted = info?.posted,
            favoriteCount = d?.favoriteCount ?: 0,
            rating = info?.rating ?: 0f,
            ratingCount = d?.ratingCount ?: 0,
            favorited = d?.isFavorited == true,
            favoriteName = info?.favoriteName,
            localFavorites = localFavorites,
            torrentCount = d?.torrentCount ?: 0,
            downloadState = downloadState,
            hasNewerVersions = d?.newerVersions?.isNotEmpty() == true,
            newerVersions = d?.newerVersions?.map { it.gid.toString() to it.token.orEmpty() }.orEmpty(),
            tags = buildTags(d?.tags),
            comments = buildComments(d?.comments?.comments),
            commentsTotal = d?.comments?.comments?.size ?: 0,
            previews = buildPreviews(d),
            previewNum = Settings.previewNum,
            previewPages = d?.previewPages ?: 0,
            startPage = startPage,
            loggedIn = (d?.apiUid ?: -1) >= 0,
            version = version,
        )
    }

    private fun bumpVersion() {
        version++
    }

    private fun buildTags(tags: Array<GalleryTagGroup>?): List<TagGroupUi> {
        if (tags.isNullOrEmpty()) return emptyList()
        val ctx = context ?: return emptyList()
        val translate = Settings.showTagTranslations && EhTagDatabase.isTranslatable(ctx)
        return tags.map { group ->
            val groupName = group.groupName.orEmpty()
            TagGroupUi(
                name = if (translate) {
                    EhTagDatabase.getTranslation(tag = groupName) ?: groupName
                } else {
                    groupName
                },
                tags = group.map { raw ->
                    var name = raw
                    var weak = false
                    var locked = false
                    var status = TagStatus.NONE
                    if (name.startsWith(TAG_PREFIX_WEAK)) {
                        weak = true
                        name = name.substring(1)
                    }
                    if (name.startsWith(TAG_PREFIX_LOCK)) {
                        locked = true
                        name = name.substring(1)
                    }
                    when {
                        name.startsWith(TAG_PREFIX_UP) -> {
                            status = TagStatus.UP
                            name = name.substring(1)
                        }
                        name.startsWith(TAG_PREFIX_DN) -> {
                            status = TagStatus.DOWN
                            name = name.substring(1)
                        }
                    }
                    val fullTag = "$groupName:$name"
                    val display = buildString {
                        if (translate) {
                            val prefix = EhTagDatabase.namespaceToPrefix(groupName)
                            append(EhTagDatabase.getTranslation(prefix, name) ?: name)
                        } else {
                            append(name)
                        }
                        when (status) {
                            TagStatus.UP -> append(TAG_STATUS_UP)
                            TagStatus.DOWN -> append(TAG_STATUS_DN)
                        }
                    }
                    TagUi(tag = fullTag, display = display, status = status, weak = weak, locked = locked)
                },
            )
        }
    }

    private fun buildComments(comments: Array<GalleryComment>?): List<CommentUi> {
        if (comments.isNullOrEmpty()) return emptyList()
        return comments.take(2).map { comment ->
            CommentUi(
                id = comment.id,
                user = comment.user.orEmpty(),
                timeAgo = ReadableTime.getTimeAgo(comment.time),
                content = runCatching {
                    HtmlCompat.fromHtml(
                        comment.comment.orEmpty(),
                        HtmlCompat.FROM_HTML_MODE_LEGACY,
                    )
                }.getOrNull(),
                score = comment.score,
            )
        }
    }

    private fun buildPreviews(d: GalleryDetail?): List<PreviewUi> {
        val set: PreviewSet = d?.previewSet ?: return emptyList()
        val size = set.size()
        if (size == 0) return emptyList()
        val count = Settings.previewNum.coerceAtMost(size)
        return (0 until count).mapNotNull { index ->
            val preview = set.getGalleryPreview(gid, index) ?: return@mapNotNull null
            PreviewUi(
                index = index,
                position = preview.position,
                imageKey = preview.imageKey.orEmpty(),
                imageUrl = preview.imageUrl.orEmpty(),
            )
        }
    }

    // endregion

    // region 数据装载

    private fun handleArgs(args: Bundle?) {
        args ?: return
        when (args.getString(KEY_ACTION)) {
            ACTION_GALLERY_INFO -> {
                val info = args.getParcelableCompat<GalleryInfo>(KEY_GALLERY_INFO) ?: return
                galleryInfo = info
                gid = info.gid
                token = info.token
                page = 0
                EhDB.putHistoryInfo(info)
            }
            ACTION_GID_TOKEN -> {
                gid = args.getLong(KEY_GID, -1L)
                token = args.getString(KEY_TOKEN)
                page = args.getInt(KEY_PAGE, 0)
            }
        }
    }

    private fun prepareData(): Boolean {
        if (detail != null) return true
        if (gid == -1L) return false
        galleryDetailCache.get(gid)?.let { detail = it }
        if (detail != null) return true
        if (requestId != -1 && application.containGlobalStuff(requestId)) return true
        request()
        return true
    }

    private fun request() {
        val url = galleryDetailUrl ?: return
        val ctx = context ?: return
        val listener = GetGalleryDetailListener(ctx)
        requestId = application.putGlobalStuff(listener)
        EhRequest()
            .setMethod(EhClient.METHOD_GET_GALLERY_DETAIL)
            .setArgs(url)
            .setCallback(listener)
            .enqueue(this)
    }

    private fun retry() {
        phase = DetailPhase.REFRESH
        request()
    }

    private fun refresh() {
        if (gid == -1L) return
        phase = if (detail != null) DetailPhase.REFRESH_HEADER else DetailPhase.REFRESH
        request()
    }

    private fun updateStartPage() {
        val currentGid = gid
        if (currentGid == -1L || downloadState != DownloadInfo.STATE_FINISH) return
        lifecycleScope.launchIO {
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

    // endregion

    // region 监听

    private inner class GetGalleryDetailListener(
        context: Context,
    ) : EhCallback<GalleryDetailScene, GalleryDetail>(context) {
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

    private inner class VoteTagListener(
        context: Context,
    ) : EhCallback<GalleryDetailScene, Pair<String, Array<GalleryTagGroup>?>>(context) {
        override fun onSuccess(result: Pair<String, Array<GalleryTagGroup>?>) {
            if (result.first.isNotEmpty()) {
                showTip(result.first, LENGTH_SHORT)
            } else {
                detail?.tags = result.second
                showTip(R.string.tag_vote_successfully, LENGTH_SHORT)
                bumpVersion()
            }
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.vote_failed, LENGTH_LONG)
        }

        override fun onCancel() {}
    }

    private inner class RateGalleryListener(
        context: Context,
    ) : EhCallback<GalleryDetailScene, RateGalleryParser.Result>(context) {
        override fun onSuccess(result: RateGalleryParser.Result) {
            detail?.let {
                it.rating = result.rating
                it.ratingCount = result.ratingCount
            }
            showTip(R.string.rate_successfully, LENGTH_SHORT)
            bumpVersion()
        }

        override fun onFailure(e: Exception) {
            Log.e(TAG, "Rate gallery failed", e)
            showTip(R.string.rate_failed, LENGTH_LONG)
        }

        override fun onCancel() {}
    }

    private inner class ModifyFavoritesListener(
        context: Context,
        private val addOrRemove: Boolean,
    ) : EhCallback<GalleryDetailScene, Unit>(context) {
        override fun onSuccess(result: Unit) {
            modifyingFavorites = false
            updateLocalFavorites()
            detail?.isFavorited = !addOrRemove && detail?.favoriteName != null
            showTip(
                if (addOrRemove) R.string.remove_from_favorite_success else R.string.add_to_favorite_success,
                LENGTH_SHORT,
            )
            bumpVersion()
        }

        override fun onFailure(e: Exception) {
            modifyingFavorites = false
            showTip(
                if (addOrRemove) R.string.remove_from_favorite_failure else R.string.add_to_favorite_failure,
                LENGTH_LONG,
            )
        }

        override fun onCancel() {
            modifyingFavorites = false
        }
    }

    // endregion

    // region 交互

    private fun downloadToDefaultDir() {
        val d = detail ?: galleryInfo ?: return
        val activity = mainActivity ?: return
        CommonOperations.startDownload(activity, d, true)
    }

    private fun onDownloadClick() {
        val activity = mainActivity ?: return
        val d = detail ?: galleryInfo ?: return
        if (downloadState == DownloadInfo.STATE_INVALID) {
            CommonOperations.startDownload(activity, d, false)
        } else {
            showDeleteDownloadDialog()
        }
    }

    private fun showDeleteDownloadDialog() {
        val ctx = context ?: return
        val title = (detail ?: galleryInfo)?.let { EhUtils.getSuitableTitle(it) }.orEmpty()
        val builder = CheckBoxDialogBuilder(
            ctx,
            getString(R.string.download_remove_dialog_message, title),
            getString(R.string.download_remove_dialog_check_text),
            Settings.removeImageFiles,
        )
        builder.setTitle(R.string.download_remove_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val currentGid = gid
                DownloadManager.deleteDownload(currentGid)
                val checked = builder.isChecked
                Settings.putRemoveImageFiles(checked)
                if (checked) {
                    val file = com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir(currentGid)
                    DownloadManager.removeDownloadDirname(currentGid)
                    lifecycleScope.launchIO {
                        runCatching { file?.delete() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun read() {
        val activity = mainActivity ?: return
        val info = detail ?: galleryInfo ?: return
        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_EH
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
        startActivity(intent)
    }

    private fun openPreviewPage(index: Int) {
        val activity = mainActivity ?: return
        val info = detail ?: galleryInfo ?: return
        val set = detail?.previewSet
        val page = set?.getGalleryPreview(gid, index)?.position ?: index
        val intent = Intent(activity, GalleryActivity::class.java)
        intent.action = GalleryActivity.ACTION_EH
        intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, info)
        intent.putExtra(GalleryActivity.KEY_PAGE, page)
        startActivity(intent)
    }

    private fun showGalleryInfo() {
        if (detail == null) return
        ehNavigator().push(EhRoute.GalleryInfo(gid = gid))
    }

    private fun showComments() {
        val d = detail ?: return
        if (mainActivity == null) return
        ehNavigator().push(
            EhRoute.GalleryComments(
                gid = d.gid,
                apiUid = d.apiUid,
                apiKey = d.apiKey,
                token = d.token,
            ),
        )
    }

    private fun showPreviews() {
        if (detail == null && galleryInfo == null) return
        ehNavigator().push(
            EhRoute.GalleryPreviews(
                gid = gid,
                scrollTo = if ((detail?.previewPages ?: 0) > 1) -1 else Settings.previewNum,
            ),
        )
    }

    private fun showNewerVersions() {
        val ctx = context ?: return
        val versions = detail?.newerVersions ?: return
        if (versions.isEmpty()) return
        val items = versions.map { it.gid.toString() }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.newer_version_title)
            .setItems(items) { _, which ->
                val info = versions[which]
                ehNavigator().push(EhRoute.GalleryDetail(gid = info.gid, token = info.token))
            }
            .show()
    }

    private fun modifyFavorites(foreSelect: Boolean) {
        val activity = mainActivity ?: return
        val d = detail ?: return
        if (modifyingFavorites) return
        modifyingFavorites = true
        if (localFavorites) {
            CommonOperations.removeFromFavorites(activity, d, ModifyFavoritesListener(activity, true), foreSelect)
        } else {
            CommonOperations.addToFavorites(activity, d, ModifyFavoritesListener(activity, false), foreSelect)
        }
        bumpVersion()
    }

    private fun share() {
        val activity = mainActivity ?: return
        val url = galleryDetailUrl ?: return
        AppHelper.share(activity, url)
    }

    private fun openInOtherApp() {
        val ctx = context ?: return
        val url = galleryDetailUrl ?: return
        UrlOpener.openUrl(ctx, url, false)
    }

    private fun openUploader() {
        val d = detail ?: galleryInfo ?: return
        val uploader = d.uploader ?: return
        if (d.disowned) return
        val lub = ListUrlBuilder(ListUrlBuilder.MODE_UPLOADER, uploader)
        GalleryListScene.open(this, lub)
    }

    private fun openCategory() {
        val d = detail ?: galleryInfo ?: return
        val category = d.category
        if (category == EhUtils.NONE || category == EhUtils.PRIVATE || category == EhUtils.UNKNOWN) return
        val lub = ListUrlBuilder()
        lub.mode = ListUrlBuilder.MODE_NORMAL
        lub.category = category
        GalleryListScene.open(this, lub)
    }

    private fun openTagList(tag: TagUi) {
        val lub = ListUrlBuilder()
        lub.mode = ListUrlBuilder.MODE_TAG
        lub.keyword = tag.tag
        GalleryListScene.open(this, lub)
    }

    private fun tagPlainName(tag: TagUi): String = tag.tag.substringAfter(':', tag.tag)

    private fun addTagFilter(tag: String) {
        val filter = Filter()
        filter.mode = EhFilter.MODE_TAG
        filter.text = tag
        EhFilter.addFilter(filter)
        showTip(R.string.filter_added, LENGTH_SHORT)
    }

    private fun showFilterUploaderDialog() {
        val ctx = context ?: return
        val uploader = (detail ?: galleryInfo)?.uploader ?: return
        if (uploader.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.filter_the_uploader, uploader))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val filter = Filter()
                filter.mode = EhFilter.MODE_UPLOADER
                filter.text = uploader
                EhFilter.addFilter(filter)
                showTip(R.string.filter_added, LENGTH_SHORT)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddTagDialog() {
        val ctx = context ?: return
        val d = detail ?: return
        if (d.apiUid < 0) {
            showTip(R.string.error_please_login_first, LENGTH_LONG)
            return
        }
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.action_add_tag_tip)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(ctx).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.action_add_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    showTip(R.string.text_is_empty, LENGTH_SHORT)
                } else {
                    voteTag(TagUi(tag = text, display = text), 1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearImageCache() {
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
        showTip(R.string.action_image_cache_cleared, LENGTH_SHORT)
    }

    private fun showSimilarGalleryList() {
        val d = detail ?: return
        val keyword = EhUtils.extractTitle(d.title)
        if (!keyword.isNullOrEmpty()) {
            val lub = ListUrlBuilder()
            lub.mode = ListUrlBuilder.MODE_NORMAL
            lub.keyword = "\"$keyword\""
            GalleryListScene.open(this, lub)
            return
        }
        val artist = getArtist(d.tags)
        if (artist != null) {
            val lub = ListUrlBuilder()
            lub.mode = ListUrlBuilder.MODE_TAG
            lub.keyword = "artist:$artist"
            GalleryListScene.open(this, lub)
            return
        }
        val uploader = d.uploader
        if (!uploader.isNullOrEmpty()) {
            val lub = ListUrlBuilder(ListUrlBuilder.MODE_UPLOADER, uploader)
            GalleryListScene.open(this, lub)
        }
    }

    private fun voteTag(tag: TagUi, vote: Int) {
        val ctx = context ?: return
        val d = detail ?: return
        EhRequest()
            .setMethod(EhClient.METHOD_VOTE_TAG)
            .setArgs(d.apiUid, d.apiKey, d.gid, d.token, tag.tag, vote)
            .setCallback(VoteTagListener(ctx))
            .enqueue(this)
    }

    // endregion

    // region 归档 / 种子 / 评分（沿用原 XML 对话框）

    private fun showRateDialog() {
        val ctx = context ?: return
        val d = detail ?: return
        if (d.apiUid < 0) {
            showTip(R.string.error_please_login_first, LENGTH_LONG)
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_rate, null)
        val ratingText = view.findViewById<android.widget.TextView>(R.id.rating_text)
        val ratingBar = view.findViewById<com.hippo.ehviewer.widget.GalleryRatingBar>(R.id.rating_view)
        ratingText.text = getString(getRatingText(d.rating))
        ratingBar.rating = d.rating
        ratingBar.setOnUserRateListener { rating ->
            ratingText.text = getString(getRatingText(rating))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.rate)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                EhRequest()
                    .setMethod(EhClient.METHOD_GET_RATE_GALLERY)
                    .setArgs(d.apiUid, d.apiKey, d.gid, d.token, ratingBar.rating)
                    .setCallback(RateGalleryListener(ctx))
                    .enqueue(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showArchiveList() {
        val ctx = context ?: return
        val d = detail ?: return
        if (d.apiUid < 0) {
            showTip(R.string.error_please_login_first, LENGTH_LONG)
            return
        }
        val url = d.archiveUrl ?: return
        val view = layoutInflater.inflate(R.layout.dialog_archive_list, null)
        val progress = view.findViewById<android.view.View>(R.id.progress)
        val errorText = view.findViewById<android.widget.TextView>(R.id.text)
        val listView = view.findViewById<android.widget.ListView>(R.id.list_view)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        fun bind(data: List<com.hippo.ehviewer.client.parser.ArchiveParser.Archive>?, funds: com.hippo.ehviewer.client.parser.HomeParser.Funds?) {
            if (data.isNullOrEmpty()) {
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.VISIBLE
                listView.visibility = android.view.View.GONE
                errorText.setText(R.string.no_archives)
            } else {
                val names = data.map {
                    if (it.isHAtH) {
                        val cost = if (it.cost == "Free") getString(R.string.archive_free) else it.cost
                        "[H@H] ${it.name} [${it.size}] [$cost]"
                    } else {
                        val nameStr = getString(
                            if (it.res == "org") R.string.archive_original else R.string.archive_resample,
                        )
                        val cost = if (it.cost == "Free!") getString(R.string.archive_free) else it.cost
                        "$nameStr [${it.size}] [$cost]"
                    }
                }.toTypedArray()
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.GONE
                listView.visibility = android.view.View.VISIBLE
                listView.adapter = android.widget.ArrayAdapter(ctx, R.layout.item_select_dialog, names)
                if (funds != null) {
                    var gp = funds.fundsGP.toString()
                    if (EhUtils.isExHentai) gp += "+"
                    dialog.setTitle(getString(R.string.current_funds, gp, funds.fundsC))
                }
                listView.setOnItemClickListener { _, _, position, _ ->
                    val archive = data[position]
                    EhRequest()
                        .setMethod(EhClient.METHOD_DOWNLOAD_ARCHIVE)
                        .setArgs(d.gid, d.token, archive.res, archive.isHAtH)
                        .setCallback(DownloadArchiveListener(ctx, d))
                        .enqueue(this)
                    dialog.dismiss()
                }
            }
        }

        if (archiveList != null) {
            bind(archiveList, currentFunds)
        } else {
            progress.visibility = android.view.View.VISIBLE
            errorText.visibility = android.view.View.GONE
            listView.visibility = android.view.View.GONE
            val request = EhRequest()
                .setMethod(EhClient.METHOD_ARCHIVE_LIST)
                .setArgs(url, gid, token)
                .setCallback(object : EhCallback<GalleryDetailScene, com.hippo.ehviewer.client.parser.ArchiveParser.Result>(ctx) {
                    override fun onSuccess(result: com.hippo.ehviewer.client.parser.ArchiveParser.Result) {
                        archiveList = result.archiveList
                        currentFunds = result.funds
                        bind(result.archiveList, result.funds)
                    }

                    override fun onFailure(e: Exception) {
                        if (!dialog.isShowing) return
                        progress.visibility = android.view.View.GONE
                        errorText.visibility = android.view.View.VISIBLE
                        listView.visibility = android.view.View.GONE
                        errorText.text = ExceptionUtils.getReadableString(e)
                    }

                    override fun onCancel() {}
                })
            request.enqueue(this)
        }
    }

    private fun showTorrentList() {
        val ctx = context ?: return
        val d = detail ?: return
        val url = d.torrentUrl ?: return
        val view = layoutInflater.inflate(R.layout.dialog_torrent_list, null)
        val progress = view.findViewById<android.view.View>(R.id.progress)
        val errorText = view.findViewById<android.widget.TextView>(R.id.text)
        val listView = view.findViewById<android.widget.ListView>(R.id.list_view)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        fun bind(data: List<com.hippo.ehviewer.client.parser.TorrentParser.Result>?) {
            if (data.isNullOrEmpty()) {
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.VISIBLE
                listView.visibility = android.view.View.GONE
                errorText.setText(R.string.no_torrents)
            } else {
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.GONE
                listView.visibility = android.view.View.VISIBLE
                listView.adapter = android.widget.ArrayAdapter(
                    ctx,
                    R.layout.item_select_dialog,
                    data.map { it.format() }.toTypedArray(),
                )
                listView.setOnItemClickListener { _, _, position, _ ->
                    val torrent = data[position]
                    val request = android.app.DownloadManager.Request(
                        torrent.url.replace("exhentai.org/torrent", "ehtracker.org/get").toUri(),
                    )
                    request.setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        com.hippo.yorozuya.FileUtils.sanitizeFilename("${torrent.name}.torrent"),
                    )
                    request.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                    request.addRequestHeader(
                        "Cookie",
                        com.hippo.ehviewer.client.EhCookieStore.getCookieHeader(torrent.url.toHttpUrl()),
                    )
                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    try {
                        dm.enqueue(request)
                        showTip(R.string.download_torrent_started, LENGTH_SHORT)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error", e)
                        ExceptionUtils.throwIfFatal(e)
                        showTip(R.string.download_torrent_failure, LENGTH_SHORT)
                    }
                    dialog.dismiss()
                }
            }
        }

        if (torrentList != null) {
            bind(torrentList)
        } else {
            progress.visibility = android.view.View.VISIBLE
            errorText.visibility = android.view.View.GONE
            listView.visibility = android.view.View.GONE
            val request = EhRequest()
                .setMethod(EhClient.METHOD_GET_TORRENT_LIST)
                .setArgs(url, gid, token)
                .setCallback(object : EhCallback<GalleryDetailScene, List<com.hippo.ehviewer.client.parser.TorrentParser.Result>>(ctx) {
                    override fun onSuccess(result: List<com.hippo.ehviewer.client.parser.TorrentParser.Result>) {
                        torrentList = result
                        bind(result)
                    }

                    override fun onFailure(e: Exception) {
                        if (!dialog.isShowing) return
                        progress.visibility = android.view.View.GONE
                        errorText.visibility = android.view.View.VISIBLE
                        listView.visibility = android.view.View.GONE
                        errorText.text = ExceptionUtils.getReadableString(e)
                    }

                    override fun onCancel() {}
                })
            request.enqueue(this)
        }
    }

    private inner class DownloadArchiveListener(
        context: Context,
        private val info: GalleryInfo,
    ) : EhCallback<GalleryDetailScene, String?>(context) {
        override fun onSuccess(result: String?) {
            result?.let { url ->
                val activity = mainActivity ?: return
                val intent = Intent().apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(url.toUri(), "application/zip")
                }
                val name = "${info.gid}-${EhUtils.getSuitableTitle(info)}.zip"
                try {
                    try {
                        activity.startActivity(intent)
                        activity.addTextToClipboard(name, false)
                    } catch (_: android.content.ActivityNotFoundException) {
                        val request = android.app.DownloadManager.Request(url.toUri())
                        request.setDestinationInExternalPublicDir(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                            com.hippo.yorozuya.FileUtils.sanitizeFilename(name),
                        )
                        request.setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                        )
                        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        dm.enqueue(request)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error", e)
                    ExceptionUtils.throwIfFatal(e)
                }
            }
            showTip(R.string.download_archive_started, LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            showTip(R.string.download_archive_failure, LENGTH_LONG)
            Log.e(TAG, "Error", e)
        }

        override fun onCancel() {}
    }

    // endregion

    private fun getRatingText(rating: Float): Int = when ((rating * 2).roundToInt()) {
        1 -> R.string.rating1
        2 -> R.string.rating2
        3 -> R.string.rating3
        4 -> R.string.rating4
        5 -> R.string.rating5
        6 -> R.string.rating6
        7 -> R.string.rating7
        8 -> R.string.rating8
        9 -> R.string.rating9
        10 -> R.string.rating10
        else -> R.string.rating_none
    }

    private fun getArtist(tags: Array<GalleryTagGroup>?): String? {
        tags ?: return null
        tags.forEach { group ->
            if (group.groupName == "artist") {
                return group.firstOrNull()?.trim()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "GalleryDetailScene"

        const val KEY_ACTION = "action"
        const val ACTION_GALLERY_INFO = "action_gallery_info"
        const val ACTION_GID_TOKEN = "action_gid_token"
        const val KEY_GALLERY_INFO = "gallery_info"
        const val KEY_GID = "gid"
        const val KEY_TOKEN = "token"
        const val KEY_PAGE = "page"

        private const val TAG_PREFIX_WEAK = "_W"
        private const val TAG_PREFIX_LOCK = "_L"
        private const val TAG_PREFIX_UP = "_U"
        private const val TAG_PREFIX_DN = "_D"
        private const val TAG_STATUS_UP = " ↑"
        private const val TAG_STATUS_DN = " ↓"
    }
}
