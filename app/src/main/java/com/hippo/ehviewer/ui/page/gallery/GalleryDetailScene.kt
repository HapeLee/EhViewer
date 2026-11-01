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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.app.CheckBoxDialogBuilder
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
import com.hippo.ehviewer.ui.compose.screen.CommentUi
import com.hippo.ehviewer.ui.compose.screen.GalleryDetailScreen
import com.hippo.ehviewer.ui.compose.screen.GalleryDetailUiState
import com.hippo.ehviewer.ui.compose.screen.PreviewUi
import com.hippo.ehviewer.ui.compose.screen.TagGroupUi
import com.hippo.ehviewer.ui.compose.screen.TagStatus
import com.hippo.ehviewer.ui.compose.screen.TagUi
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
import kotlin.math.roundToInt
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

    fun showDeleteDownloadDialog() {
        val title = (vm.detail ?: vm.galleryInfo)?.let { EhUtils.getSuitableTitle(it) }.orEmpty()
        val builder = CheckBoxDialogBuilder(
            context,
            resources.getString(R.string.download_remove_dialog_message, title),
            resources.getString(R.string.download_remove_dialog_check_text),
            Settings.removeImageFiles,
        )
        builder.setTitle(R.string.download_remove_dialog_title)
            .setPositiveButton(android.R.string.ok) { _, _ -> vm.deleteDownload(builder.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showRateDialog() {
        val d = vm.detail ?: return
        if (d.apiUid < 0) {
            host.showTip(resources.getString(R.string.error_please_login_first), TIP_LENGTH_LONG)
            return
        }
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_rate, null)
        val ratingText = view.findViewById<android.widget.TextView>(R.id.rating_text)
        val ratingBar = view.findViewById<com.hippo.ehviewer.widget.GalleryRatingBar>(R.id.rating_view)
        ratingText.text = resources.getString(ratingTextRes(d.rating))
        ratingBar.rating = d.rating
        ratingBar.setOnUserRateListener { rating ->
            ratingText.text = resources.getString(ratingTextRes(rating))
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.rate)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> vm.rate(ratingBar.rating) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showArchiveList() {
        val d = vm.detail ?: return
        if (d.apiUid < 0) {
            host.showTip(resources.getString(R.string.error_please_login_first), TIP_LENGTH_LONG)
            return
        }
        if (d.archiveUrl == null) return
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_archive_list, null)
        val progress = view.findViewById<android.view.View>(R.id.progress)
        val errorText = view.findViewById<android.widget.TextView>(R.id.text)
        val listView = view.findViewById<android.widget.ListView>(R.id.list_view)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        fun bind(data: List<ArchiveParser.Archive>, funds: com.hippo.ehviewer.client.parser.HomeParser.Funds?) {
            if (data.isEmpty()) {
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.VISIBLE
                listView.visibility = android.view.View.GONE
                errorText.setText(R.string.no_archives)
                return
            }
            val names = data.map {
                if (it.isHAtH) {
                    val cost = if (it.cost == "Free") resources.getString(R.string.archive_free) else it.cost
                    "[H@H] ${it.name} [${it.size}] [$cost]"
                } else {
                    val nameStr = resources.getString(
                        if (it.res == "org") R.string.archive_original else R.string.archive_resample,
                    )
                    val cost = if (it.cost == "Free!") resources.getString(R.string.archive_free) else it.cost
                    "$nameStr [${it.size}] [$cost]"
                }
            }.toTypedArray()
            progress.visibility = android.view.View.GONE
            errorText.visibility = android.view.View.GONE
            listView.visibility = android.view.View.VISIBLE
            listView.adapter = android.widget.ArrayAdapter(context, R.layout.item_select_dialog, names)
            if (funds != null) {
                var gp = funds.fundsGP.toString()
                if (EhUtils.isExHentai) gp += "+"
                dialog.setTitle(resources.getString(R.string.current_funds, gp, funds.fundsC))
            }
            listView.setOnItemClickListener { _, _, position, _ ->
                vm.downloadArchive(
                    data[position],
                    onSuccess = { url -> startArchiveDownload(context, activity, d, url, host) },
                    onFailure = {
                        host.showTip(resources.getString(R.string.download_archive_failure), TIP_LENGTH_LONG)
                    },
                )
                dialog.dismiss()
            }
        }

        progress.visibility = android.view.View.VISIBLE
        errorText.visibility = android.view.View.GONE
        listView.visibility = android.view.View.GONE
        vm.loadArchives(
            onSuccess = { data, funds -> bind(data, funds) },
            onFailure = { e ->
                if (!dialog.isShowing) return@loadArchives
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.VISIBLE
                listView.visibility = android.view.View.GONE
                errorText.text = ExceptionUtils.getReadableString(e)
            },
        )
    }

    fun showTorrentList() {
        val d = vm.detail ?: return
        if (d.torrentUrl == null) return
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_torrent_list, null)
        val progress = view.findViewById<android.view.View>(R.id.progress)
        val errorText = view.findViewById<android.widget.TextView>(R.id.text)
        val listView = view.findViewById<android.widget.ListView>(R.id.list_view)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        fun bind(data: List<TorrentParser.Result>) {
            if (data.isEmpty()) {
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.VISIBLE
                listView.visibility = android.view.View.GONE
                errorText.setText(R.string.no_torrents)
                return
            }
            progress.visibility = android.view.View.GONE
            errorText.visibility = android.view.View.GONE
            listView.visibility = android.view.View.VISIBLE
            listView.adapter = android.widget.ArrayAdapter(
                context,
                R.layout.item_select_dialog,
                data.map { it.format() }.toTypedArray(),
            )
            listView.setOnItemClickListener { _, _, position, _ ->
                startTorrentDownload(context, data[position], host)
                dialog.dismiss()
            }
        }

        progress.visibility = android.view.View.VISIBLE
        errorText.visibility = android.view.View.GONE
        listView.visibility = android.view.View.GONE
        vm.loadTorrents(
            onSuccess = { bind(it) },
            onFailure = { e ->
                if (!dialog.isShowing) return@loadTorrents
                progress.visibility = android.view.View.GONE
                errorText.visibility = android.view.View.VISIBLE
                listView.visibility = android.view.View.GONE
                errorText.text = ExceptionUtils.getReadableString(e)
            },
        )
    }

    fun showFilterUploaderDialog() {
        val uploader = (vm.detail ?: vm.galleryInfo)?.uploader ?: return
        if (uploader.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setMessage(resources.getString(R.string.filter_the_uploader, uploader))
            .setPositiveButton(android.R.string.ok) { _, _ ->
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
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showAddTagDialog() {
        val d = vm.detail ?: return
        if (d.apiUid < 0) {
            host.showTip(resources.getString(R.string.error_please_login_first), TIP_LENGTH_LONG)
            return
        }
        val input = android.widget.EditText(context).apply {
            hint = resources.getString(R.string.action_add_tag_tip)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(context).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.action_add_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    host.showTip(resources.getString(R.string.text_is_empty), TIP_LENGTH_SHORT)
                } else {
                    vm.voteTag(TagUi(tag = text, display = text), 1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    GalleryDetailScreen(
        state = vm.uiState(context),
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
                showDeleteDownloadDialog()
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
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.newer_version_title)
                .setItems(versions.map { it.gid.toString() }.toTypedArray()) { _, which ->
                    val info = versions[which]
                    navigator.push(EhRoute.GalleryDetail(gid = info.gid, token = info.token))
                }
                .show()
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

private fun ratingTextRes(rating: Float): Int = when ((rating * 2).roundToInt()) {
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

/** 详情页的不可变快照；`version` 用来感知 `GalleryDetail` 的就地修改 */
private fun GalleryDetailViewModel.uiState(context: Context): GalleryDetailUiState {
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
        tags = buildTags(context, d?.tags),
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

private const val TAG_PREFIX_WEAK = "_W"
private const val TAG_PREFIX_LOCK = "_L"
private const val TAG_PREFIX_UP = "_U"
private const val TAG_PREFIX_DN = "_D"
private const val TAG_STATUS_UP = " \u2191"
private const val TAG_STATUS_DN = " \u2193"

private fun buildTags(context: Context, tags: Array<GalleryTagGroup>?): List<TagGroupUi> {
    if (tags.isNullOrEmpty()) return emptyList()
    val translate = Settings.showTagTranslations && EhTagDatabase.isTranslatable(context)
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
