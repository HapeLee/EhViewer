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
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.UrlOpener
import com.hippo.ehviewer.client.EhFilter
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
import com.hippo.ehviewer.client.parser.TorrentParser
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.dao.Filter
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.nav.galleryQueryOf
import com.hippo.ehviewer.ui.compose.screen.ArchiveListDialog
import com.hippo.ehviewer.ui.compose.screen.ArchiveListState
import com.hippo.ehviewer.ui.compose.screen.CommentUi
import com.hippo.ehviewer.ui.compose.screen.ConfirmDialog
import com.hippo.ehviewer.ui.compose.screen.DeleteDownloadDialog
import com.hippo.ehviewer.ui.compose.screen.GalleryDetailScreen
import com.hippo.ehviewer.ui.compose.screen.GalleryDetailUiState
import com.hippo.ehviewer.ui.compose.screen.ListChoiceDialog
import com.hippo.ehviewer.ui.compose.screen.PreviewUi
import com.hippo.ehviewer.ui.compose.screen.RateDialog
import com.hippo.ehviewer.ui.compose.screen.TagGroupUi
import com.hippo.ehviewer.ui.compose.screen.TagStatus
import com.hippo.ehviewer.ui.compose.screen.TagUi
import com.hippo.ehviewer.ui.compose.screen.TextInputDialog
import com.hippo.ehviewer.ui.compose.screen.TorrentListDialog
import com.hippo.ehviewer.ui.compose.screen.TorrentListState
import com.hippo.ehviewer.ui.host.OnHostResume
import com.hippo.ehviewer.ui.host.TIP_LENGTH_LONG
import com.hippo.ehviewer.ui.host.TIP_LENGTH_SHORT
import com.hippo.ehviewer.ui.host.ehHostOrNoOp
import com.hippo.util.AppHelper
import com.hippo.util.ExceptionUtils
import com.hippo.util.ReadableTime
import com.hippo.util.addTextToClipboard
import com.hippo.util.launchIO
import com.hippo.util.withUIContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 画廊详情页。
 *
 * P7-c 出壳：数据装载、收藏 / 下载 / 评分 / 标签投票、归档与种子请求全部搬进
 * [GalleryDetailViewModel]；这里保留界面绑定、导航与四个「一次性」对话框
 * （评分 / 归档 / 种子 / 删除下载）。
 *
 * 为什么对话框留在场景里：它们一次弹出、一次关闭，丢掉也不会影响页面状态——
 * 与出壳前一致（旧实现的 `AlertDialog` 同样活不过重建）。请求本身仍走 ViewModel，
 * 旋转时不会取消。
 *
 * 已知差异：`onProvideAssistContent` 的 `webUri` 没有对应钩子，暂时不设置。
 */
@Composable
fun GalleryDetailScene(route: EhRoute.GalleryDetail) {
    val context = LocalContext.current
    // 事件回调里读资源用 LocalResources：LocalContext.current 不随 Configuration 变化失效
    val resources = LocalResources.current
    val activity = context as? MainActivity
    val host = ehHostOrNoOp()
    val navigator = ehNavigator()
    val vm = viewModel { GalleryDetailViewModel(context.applicationContext) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm, route) { vm.start(route) }

    LaunchedEffect(vm.tip) {
        val tip = vm.consumeTip()
        if (tip != null) host.showTip(tip, vm.tipLength)
    }

    OnHostResume { vm.syncOnResume() }

    // 一次性的对话框状态：置空即关闭，与旧「AlertDialog 活不过重建」等价
    var deleteDownloadDialog by remember { mutableStateOf(false) }
    var rateDialog by remember { mutableStateOf(false) }
    var archiveDialog by remember { mutableStateOf<ArchiveListState?>(null) }
    var torrentDialog by remember { mutableStateOf<TorrentListState?>(null) }
    var filterUploaderDialog by remember { mutableStateOf(false) }
    var addTagDialog by remember { mutableStateOf(false) }
    var newerVersionDialog by remember { mutableStateOf(false) }

    fun showRateDialog() {
        val d = vm.detail ?: return
        if (d.apiUid < 0) {
            host.showTip(resources.getString(R.string.error_please_login_first), TIP_LENGTH_LONG)
            return
        }
        rateDialog = true
    }

    fun showArchiveList() {
        val d = vm.detail ?: return
        if (d.apiUid < 0) {
            host.showTip(resources.getString(R.string.error_please_login_first), TIP_LENGTH_LONG)
            return
        }
        if (d.archiveUrl == null) return
        archiveDialog = ArchiveListState.Loading
        vm.loadArchives(
            onSuccess = { data, funds ->
                val fundsTitle = funds?.let {
                    var gp = it.fundsGP.toString()
                    if (EhUtils.isExHentai) gp += "+"
                    resources.getString(R.string.current_funds, gp, it.fundsC)
                }
                archiveDialog = ArchiveListState.Ready(data, fundsTitle)
            },
            onFailure = { e -> archiveDialog = ArchiveListState.Failed(ExceptionUtils.getReadableString(e)) },
        )
    }

    fun showTorrentList() {
        val d = vm.detail ?: return
        if (d.torrentUrl == null) return
        torrentDialog = TorrentListState.Loading
        vm.loadTorrents(
            onSuccess = { torrentDialog = TorrentListState.Ready(it) },
            onFailure = { e -> torrentDialog = TorrentListState.Failed(ExceptionUtils.getReadableString(e)) },
        )
    }

    fun showFilterUploaderDialog() {
        val uploader = (vm.detail ?: vm.galleryInfo)?.uploader ?: return
        if (uploader.isEmpty()) return
        filterUploaderDialog = true
    }

    fun showAddTagDialog() {
        val d = vm.detail ?: return
        if (d.apiUid < 0) {
            host.showTip(resources.getString(R.string.error_please_login_first), TIP_LENGTH_LONG)
            return
        }
        addTagDialog = true
    }

    fun read(previewIndex: Int? = null) {
        val info = vm.detail ?: vm.galleryInfo ?: return
        val preview = previewIndex?.let { vm.detail?.previewSet?.getGalleryPreview(vm.gid, it) }
        navigator.push(
            EhRoute.Reader(
                gid = info.gid,
                token = info.token,
                page = preview?.position ?: previewIndex ?: -1,
            ),
        )
    }

    fun openGalleryList(lub: ListUrlBuilder?) {
        navigator.push(EhRoute.GalleryList(galleryQueryOf(lub)))
    }

    // 订阅「显示标签翻译」开关与翻译库版本号：页面开着时切开关、或数据刚下完，都能立刻重排标签，
    // 不用退出重进（这两个值只是订阅用的输入，真正的判断在 buildTags 里）
    val translateTagsBySetting by Settings.valueFlow(Settings.KEY_SHOW_TAG_TRANSLATIONS, false)
        .collectAsState(false)
    val tagDbVersion by EhTagDatabase.versionFlow.collectAsState(0)
    val translateTags = translateTagsBySetting && tagDbVersion > 0

    GalleryDetailScreen(
        state = vm.uiState(context, translateTags),
        onBack = { navigator.pop() },
        onRetry = vm::retry,
        onRefresh = vm::refresh,
        onAddTag = ::showAddTagDialog,
        onClearImageCache = vm::clearImageCache,
        onOpenInOtherApp = { vm.detailUrl?.let { UrlOpener.openUrl(context, it, false) } },
        onUploaderClick = {
            val d = vm.detail ?: vm.galleryInfo ?: return@GalleryDetailScreen
            val uploader = d.uploader ?: return@GalleryDetailScreen
            if (d.disowned) return@GalleryDetailScreen
            openGalleryList(ListUrlBuilder(ListUrlBuilder.MODE_UPLOADER, uploader))
        },
        onUploaderLongClick = ::showFilterUploaderDialog,
        onCategoryClick = {
            val d = vm.detail ?: vm.galleryInfo ?: return@GalleryDetailScreen
            val category = d.category
            if (category == EhUtils.NONE || category == EhUtils.PRIVATE || category == EhUtils.UNKNOWN) {
                return@GalleryDetailScreen
            }
            openGalleryList(
                ListUrlBuilder().apply {
                    mode = ListUrlBuilder.MODE_NORMAL
                    this.category = category
                },
            )
        },
        onDownloadClick = {
            if (vm.downloadState == com.hippo.ehviewer.dao.DownloadInfo.STATE_INVALID) {
                activity?.let { vm.download(false, it) }
            } else {
                deleteDownloadDialog = true
            }
        },
        onDownloadLongClick = { activity?.let { vm.download(true, it) } },
        onReadClick = { read() },
        onInfoClick = {
            if (vm.detail != null) navigator.push(EhRoute.GalleryInfo(gid = vm.gid))
        },
        onNewerVersionClick = {
            val versions = vm.detail?.newerVersions ?: return@GalleryDetailScreen
            if (versions.isEmpty()) return@GalleryDetailScreen
            newerVersionDialog = true
        },
        onFavoriteClick = { activity?.let { vm.modifyFavorites(false, it) } },
        onFavoriteLongClick = { activity?.let { vm.modifyFavorites(true, it) } },
        onShareClick = {
            val url = vm.detailUrl
            if (url != null && activity != null) AppHelper.share(activity, url)
        },
        onTorrentClick = ::showTorrentList,
        onArchiveClick = ::showArchiveList,
        onSimilarClick = {
            val d = vm.detail ?: return@GalleryDetailScreen
            val keyword = EhUtils.extractTitle(d.title)
            when {
                !keyword.isNullOrEmpty() -> openGalleryList(
                    ListUrlBuilder().apply {
                        mode = ListUrlBuilder.MODE_NORMAL
                        this.keyword = "\"$keyword\""
                    },
                )
                getArtist(d.tags) != null -> openGalleryList(
                    ListUrlBuilder().apply {
                        mode = ListUrlBuilder.MODE_TAG
                        this.keyword = "artist:${getArtist(d.tags)}"
                    },
                )
                !d.uploader.isNullOrEmpty() -> openGalleryList(
                    ListUrlBuilder(ListUrlBuilder.MODE_UPLOADER, d.uploader),
                )
            }
        },
        onRateClick = ::showRateDialog,
        onTagClick = { tag ->
            openGalleryList(
                ListUrlBuilder().apply {
                    mode = ListUrlBuilder.MODE_TAG
                    keyword = tag.tag
                },
            )
        },
        onVoteTag = vm::voteTag,
        onFilterTag = { tag ->
            scope.launchIO {
                EhFilter.addFilter(
                    Filter().apply {
                        mode = EhFilter.MODE_TAG
                        text = tag
                    },
                )
                withUIContext {
                    host.showTip(resources.getString(R.string.filter_added), TIP_LENGTH_SHORT)
                }
            }
        },
        onCopyTag = { context.addTextToClipboard(it, false) },
        onShowTagDefinition = { tag ->
            UrlOpener.openUrl(context, EhUrl.getTagDefinitionUrl(tagPlainName(tag)), false)
        },
        onCommentsClick = { showComments(vm, navigator) },
        onCommentClick = { showComments(vm, navigator) },
        onPreviewClick = { index -> read(index) },
        onPreviewsMoreClick = {
            if (vm.detail == null && vm.galleryInfo == null) return@GalleryDetailScreen
            navigator.push(
                EhRoute.GalleryPreviews(
                    gid = vm.gid,
                    scrollTo = if ((vm.detail?.previewPages ?: 0) > 1) -1 else Settings.previewNum,
                ),
            )
        },
    )

    if (deleteDownloadDialog) {
        val title = (vm.detail ?: vm.galleryInfo)?.let { EhUtils.getSuitableTitle(it) }.orEmpty()
        DeleteDownloadDialog(
            message = stringResource(R.string.download_remove_dialog_message, title),
            checkText = stringResource(R.string.download_remove_dialog_check_text),
            initialChecked = Settings.removeImageFiles,
            onConfirm = { removeImageFiles ->
                deleteDownloadDialog = false
                vm.deleteDownload(removeImageFiles)
            },
            onDismiss = { deleteDownloadDialog = false },
        )
    }

    if (rateDialog) {
        RateDialog(
            initialRating = vm.detail?.rating ?: 0f,
            onRate = { rating ->
                rateDialog = false
                vm.rate(rating)
            },
            onDismiss = { rateDialog = false },
        )
    }

    archiveDialog?.let { state ->
        ArchiveListDialog(
            state = state,
            onSelect = { archive ->
                archiveDialog = null
                vm.downloadArchive(
                    archive,
                    onSuccess = { url ->
                        vm.detail?.let { detail -> startArchiveDownload(context, activity, detail, url, host) }
                    },
                    onFailure = {
                        host.showTip(resources.getString(R.string.download_archive_failure), TIP_LENGTH_LONG)
                    },
                )
            },
            onDismiss = { archiveDialog = null },
        )
    }

    torrentDialog?.let { state ->
        TorrentListDialog(
            state = state,
            onSelect = { torrent ->
                torrentDialog = null
                startTorrentDownload(context, torrent, host)
            },
            onDismiss = { torrentDialog = null },
        )
    }

    if (filterUploaderDialog) {
        val uploader = (vm.detail ?: vm.galleryInfo)?.uploader
        if (uploader.isNullOrEmpty()) {
            filterUploaderDialog = false
        } else {
            ConfirmDialog(
                message = stringResource(R.string.filter_the_uploader, uploader),
                onConfirm = {
                    filterUploaderDialog = false
                    scope.launchIO {
                        EhFilter.addFilter(
                            Filter().apply {
                                mode = EhFilter.MODE_UPLOADER
                                text = uploader
                            },
                        )
                        withUIContext {
                            host.showTip(resources.getString(R.string.filter_added), TIP_LENGTH_SHORT)
                        }
                    }
                },
                onDismiss = { filterUploaderDialog = false },
            )
        }
    }

    if (addTagDialog) {
        TextInputDialog(
            title = stringResource(R.string.action_add_tag),
            hint = stringResource(R.string.action_add_tag_tip),
            onSubmit = { text ->
                addTagDialog = false
                vm.voteTag(TagUi(tag = text, display = text), 1)
            },
            onDismiss = { addTagDialog = false },
        )
    }

    if (newerVersionDialog) {
        val versions = vm.detail?.newerVersions.orEmpty()
        if (versions.isEmpty()) {
            newerVersionDialog = false
        } else {
            ListChoiceDialog(
                title = stringResource(R.string.newer_version_title),
                items = versions.map { it.gid.toString() },
                onSelect = { index ->
                    newerVersionDialog = false
                    val info = versions[index]
                    navigator.push(EhRoute.GalleryDetail(gid = info.gid, token = info.token))
                },
                onDismiss = { newerVersionDialog = false },
            )
        }
    }
}

private fun showComments(vm: GalleryDetailViewModel, navigator: com.hippo.ehviewer.ui.compose.nav.EhNavigator) {
    val d = vm.detail ?: return
    navigator.push(
        EhRoute.GalleryComments(
            gid = d.gid,
            apiUid = d.apiUid,
            apiKey = d.apiKey,
            token = d.token,
        ),
    )
}

private fun startArchiveDownload(
    context: Context,
    activity: MainActivity?,
    detail: GalleryDetail,
    url: String?,
    host: com.hippo.ehviewer.ui.host.EhHostState,
) {
    if (url != null && activity != null) {
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            setDataAndType(url.toUri(), "application/zip")
        }
        val name = "${detail.gid}-${EhUtils.getSuitableTitle(detail)}.zip"
        try {
            try {
                activity.startActivity(intent)
                activity.addTextToClipboard(name, false)
            } catch (_: android.content.ActivityNotFoundException) {
                enqueueSystemDownload(context, url, name)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error", e)
            ExceptionUtils.throwIfFatal(e)
        }
    }
    host.showTip(context.getString(R.string.download_archive_started), TIP_LENGTH_SHORT)
}

private fun startTorrentDownload(context: Context, torrent: TorrentParser.Result, host: com.hippo.ehviewer.ui.host.EhHostState) {
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
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
    try {
        dm.enqueue(request)
        host.showTip(context.getString(R.string.download_torrent_started), TIP_LENGTH_SHORT)
    } catch (e: Throwable) {
        Log.e(TAG, "Error", e)
        ExceptionUtils.throwIfFatal(e)
        host.showTip(context.getString(R.string.download_torrent_failure), TIP_LENGTH_SHORT)
    }
}

private fun enqueueSystemDownload(context: Context, url: String, name: String) {
    val request = android.app.DownloadManager.Request(url.toUri())
    request.setDestinationInExternalPublicDir(
        android.os.Environment.DIRECTORY_DOWNLOADS,
        com.hippo.yorozuya.FileUtils.sanitizeFilename(name),
    )
    request.setNotificationVisibility(
        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
    )
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
    dm.enqueue(request)
}

private fun tagPlainName(tag: TagUi): String = tag.tag.substringAfter(':', tag.tag)

private fun getArtist(tags: Array<GalleryTagGroup>?): String? {
    tags ?: return null
    tags.forEach { group ->
        if (group.groupName == "artist") {
            return group.firstOrNull()?.trim()
        }
    }
    return null
}

/** 详情页的不可变快照；`version` 用来感知 `GalleryDetail` 的就地修改 */
private fun GalleryDetailViewModel.uiState(context: Context, translateTags: Boolean): GalleryDetailUiState {
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
        size = d?.size,
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
        tags = buildTags(context, d?.tags, translateTags),
        comments = buildComments(d?.comments?.comments),
        commentsTotal = d?.comments?.comments?.size ?: 0,
        previews = buildPreviews(d, gid),
        previewNum = Settings.previewNum,
        previewPages = d?.previewPages ?: 0,
        startPage = startPage,
        loggedIn = (d?.apiUid ?: -1) >= 0,
        version = version,
    )
}

private const val TAG_STATUS_UP = " \u2191"
private const val TAG_STATUS_DN = " \u2193"

private fun buildTags(context: Context, tags: Array<GalleryTagGroup>?, enabled: Boolean): List<TagGroupUi> {
    if (tags.isNullOrEmpty()) return emptyList()
    val translate = enabled && EhTagDatabase.isTranslatable(context)
    return tags.map { group ->
        val groupName = group.groupName.orEmpty()
        TagGroupUi(
            name = if (translate) {
                EhTagDatabase.getTranslation(tag = groupName) ?: groupName
            } else {
                groupName
            },
            tags = group.map { raw ->
                // 标签名前面挂着解析器加的标记：_U/_D（投票）、_W（弱标签）、_L（已锁）。
                // 每个标记都是「下划线 + 字母」两位，而且可能叠在一起（顺序是 _L _W _U），
                // 必须整段剥干净——剥剩一个字母就去查翻译，永远查不到
                var name = raw
                var weak = false
                var locked = false
                var status = TagStatus.NONE
                var stripping = true
                while (stripping && name.length > 2 && name[0] == '_') {
                    when (name[1]) {
                        'W' -> weak = true
                        'L' -> locked = true
                        'U' -> status = TagStatus.UP
                        'D' -> status = TagStatus.DOWN
                        else -> stripping = false
                    }
                    if (stripping) {
                        name = name.substring(2)
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
                        TagStatus.NONE -> Unit
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
                HtmlCompat.fromHtml(comment.comment.orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY)
            }.getOrNull(),
            score = comment.score,
        )
    }
}

private fun buildPreviews(d: GalleryDetail?, gid: Long): List<PreviewUi> {
    val set: PreviewSet = d?.previewSet ?: return emptyList()
    val size = set.size()
    if (size == 0) return emptyList()
    val count = Settings.previewNum.coerceAtMost(size)
    return (0 until count).map { index ->
        val preview = set.getGalleryPreview(gid, index)
        PreviewUi(
            index = index,
            position = preview.position,
            imageKey = preview.imageKey.orEmpty(),
            imageUrl = preview.imageUrl.orEmpty(),
        )
    }
}

private const val TAG = "GalleryDetailScene"
