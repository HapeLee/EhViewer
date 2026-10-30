/*
 * Copyright 2026 EhViewer contributors
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
package com.hippo.ehviewer.ui.compose.screen

import android.text.Spanned
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.ui.compose.component.CategoryBadge
import com.hippo.ehviewer.ui.compose.component.RatingBar
import com.hippo.ehviewer.ui.compose.text.toAnnotatedString

/** 详情页的界面阶段，对应旧 `GalleryDetailScene.STATE_*` */
object DetailPhase {
    const val INIT = -1
    const val NORMAL = 0
    const val REFRESH = 1
    const val REFRESH_HEADER = 2
    const val FAILED = 3
}

/** tag 的投票状态，用于 `_U` / `_D` 后缀与菜单文案 */
object TagStatus {
    const val NONE = 0
    const val UP = 1
    const val DOWN = -1
}

data class TagUi(
    /** 完整 tag（含命名空间），用于筛选与投票 */
    val tag: String,
    val display: String,
    val status: Int = TagStatus.NONE,
    val weak: Boolean = false,
    val locked: Boolean = false,
)

data class TagGroupUi(
    val name: String,
    val tags: List<TagUi>,
)

data class CommentUi(
    val id: Long,
    val user: String,
    val timeAgo: String,
    /** 保留 `Spanned`，在组合期用主题色转换成 `AnnotatedString` */
    val content: Spanned?,
    val score: Int,
)

data class PreviewUi(
    val index: Int,
    val position: Int,
    val imageKey: String,
    val imageUrl: String,
)

data class GalleryDetailUiState(
    val phase: Int = DetailPhase.INIT,
    val error: String? = null,
    val gid: Long = -1L,
    val title: String = "",
    val uploader: String? = null,
    val disowned: Boolean = false,
    val category: Int = EhUtils.UNKNOWN,
    val thumbUrl: String? = null,
    val language: String? = null,
    val pages: Int = 0,
    val size: String? = null,
    val posted: String? = null,
    val favoriteCount: Int = 0,
    val rating: Float = 0f,
    val ratingCount: Int = 0,
    val favorited: Boolean = false,
    val favoriteName: String? = null,
    val localFavorites: Boolean = false,
    val torrentCount: Int = 0,
    val downloadState: Int = DownloadInfo.STATE_INVALID,
    val hasNewerVersions: Boolean = false,
    val newerVersions: List<Pair<String, String>> = emptyList(),
    val tags: List<TagGroupUi> = emptyList(),
    val comments: List<CommentUi> = emptyList(),
    val commentsTotal: Int = 0,
    val previews: List<PreviewUi> = emptyList(),
    val previewNum: Int = 0,
    val previewPages: Int = 0,
    val startPage: Int = -1,
    val loggedIn: Boolean = false,
    val version: Int = 0,
)

/**
 * 画廊详情页（原 `scene_gallery_detail.xml` + 四个 `gallery_detail_*.xml` 的界面部分）。
 *
 * 结构调整：
 * - 头部改为「顶栏 + 渐变信息区」：`back_action` / `other_actions` 变成 `TopAppBar` 的返回与溢出菜单，
 *   标题 / 上传者 / 分类搬进 Compose 头部；`color_bg` 的 CircularReveal 与共享元素转场跨 Scene
 *   无法保留，降级为普通转场（见迁移文档）
 * - `ViewTransition` 的多态改成 `when (phase)`：整页 loading / 头部 + 局部 loading / 内容 / 失败重试
 * - 标签从 `AutoWrapLayout` 换成 `FlowRow` chip；评论预览、预览图网格、信息行都直接读传入的快照
 * - 归档 / 种子 / 评分三个对话框仍由 Scene 用原有 XML 布局实现，这里只提供入口与回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(
    state: GalleryDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onAddTag: () -> Unit,
    onClearImageCache: () -> Unit,
    onOpenInOtherApp: () -> Unit,
    onUploaderClick: () -> Unit,
    onUploaderLongClick: () -> Unit,
    onCategoryClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDownloadLongClick: () -> Unit,
    onReadClick: () -> Unit,
    onInfoClick: () -> Unit,
    onNewerVersionClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onFavoriteLongClick: () -> Unit,
    onShareClick: () -> Unit,
    onTorrentClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onSimilarClick: () -> Unit,
    onRateClick: () -> Unit,
    onTagClick: (TagUi) -> Unit,
    onVoteTag: (TagUi, Int) -> Unit,
    onFilterTag: (String) -> Unit,
    onCopyTag: (String) -> Unit,
    onShowTagDefinition: (TagUi) -> Unit,
    onCommentsClick: () -> Unit,
    onCommentClick: (CommentUi) -> Unit,
    onPreviewClick: (Int) -> Unit,
    onPreviewsMoreClick: () -> Unit,
) {
    var overflowVisible by remember { mutableStateOf(false) }
    var tagMenuTarget by remember { mutableStateOf<TagUi?>(null) }
    var tagFilterTarget by remember { mutableStateOf<TagUi?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.v_arrow_left_dark_x24),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                Box {
                    IconButton(onClick = { overflowVisible = true }) {
                        Icon(
                            painter = painterResource(R.drawable.v_more_vert_dark_x24),
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(
                        expanded = overflowVisible,
                        onDismissRequest = { overflowVisible = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.refresh)) },
                            onClick = {
                                overflowVisible = false
                                onRefresh()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_add_tag)) },
                            onClick = {
                                overflowVisible = false
                                onAddTag()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_clear_image_cache)) },
                            onClick = {
                                overflowVisible = false
                                onClearImageCache()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_in_other_app)) },
                            onClick = {
                                overflowVisible = false
                                onOpenInOtherApp()
                            },
                        )
                    }
                }
            },
        )

        when (state.phase) {
            DetailPhase.REFRESH, DetailPhase.INIT -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            DetailPhase.FAILED -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(onClick = onRetry, onLongClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: stringResource(R.string.error_cannot_find_gallery),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                DetailHeader(state, onUploaderClick, onUploaderLongClick, onCategoryClick)
                if (state.phase == DetailPhase.REFRESH_HEADER) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    ActionCard(
                        downloadText = downloadText(state),
                        readText = if (state.startPage >= 0) {
                            stringResource(R.string.read_from, state.startPage + 1)
                        } else {
                            stringResource(R.string.read)
                        },
                        onDownloadClick = onDownloadClick,
                        onDownloadLongClick = onDownloadLongClick,
                        onReadClick = onReadClick,
                    )
                    InfoBlock(state, onInfoClick)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ActionsRow(
                        state = state,
                        onFavoriteClick = onFavoriteClick,
                        onFavoriteLongClick = onFavoriteLongClick,
                        onShareClick = onShareClick,
                        onTorrentClick = onTorrentClick,
                        onArchiveClick = onArchiveClick,
                        onSimilarClick = onSimilarClick,
                        onRateClick = onRateClick,
                        onNewerVersionClick = onNewerVersionClick,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    TagsBlock(state, onTagClick, { tagMenuTarget = it })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    CommentsBlock(state, onCommentsClick, onCommentClick)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PreviewsBlock(state, onPreviewClick, onPreviewsMoreClick)
                }
            }
        }
    }

    tagMenuTarget?.let { tag ->
        TagMenuDialog(
            tag = tag,
            loggedIn = state.loggedIn,
            onDismiss = { tagMenuTarget = null },
            onCopy = {
                tagMenuTarget = null
                onCopyTag(tag.tag)
            },
            onShowDefinition = {
                tagMenuTarget = null
                onShowTagDefinition(tag)
            },
            onAddFilter = {
                tagMenuTarget = null
                tagFilterTarget = tag
            },
            onVote = { vote ->
                tagMenuTarget = null
                onVoteTag(tag, vote)
            },
        )
    }

    tagFilterTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagFilterTarget = null },
            text = { Text(stringResource(R.string.filter_the_tag, tag.tag)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        tagFilterTarget = null
                        onFilterTag(tag.tag)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { tagFilterTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailHeader(
    state: GalleryDetailUiState,
    onUploaderClick: () -> Unit,
    onUploaderLongClick: () -> Unit,
    onCategoryClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val context = LocalContext.current
        Row {
            val thumbKey = remember(state.gid) { "preview:large:${state.gid}:0" }
            val request = remember(thumbKey, state.thumbUrl) {
                ImageRequest.Builder(context)
                    .data(state.thumbUrl)
                    .apply {
                        memoryCacheKey(thumbKey)
                        diskCacheKey(thumbKey)
                    }
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.uploader.isNullOrEmpty()) {
                    Text(
                        text = state.uploader,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.disowned) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .combinedClickable(
                                onClick = onUploaderClick,
                                onLongClick = onUploaderLongClick,
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                CategoryBadge(
                    category = state.category,
                    modifier = Modifier.combinedClickable(onClick = onCategoryClick, onLongClick = {}),
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    downloadText: String,
    readText: String,
    onDownloadClick: () -> Unit,
    onDownloadLongClick: () -> Unit,
    onReadClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.height(48.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .combinedClickable(onClick = onDownloadClick, onLongClick = onDownloadLongClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = downloadText, color = MaterialTheme.colorScheme.primary)
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .align(Alignment.CenterVertically)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .combinedClickable(onClick = onReadClick, onLongClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = readText, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun InfoBlock(state: GalleryDetailUiState, onInfoClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onInfoClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row {
            InfoText(stringResource(R.string.key_language), Modifier.weight(1f))
            InfoText(state.language.orEmpty(), Modifier.weight(1f))
        }
        Row {
            InfoText(stringResource(R.string.key_pages), Modifier.weight(1f))
            InfoText(
                text = pluralStringResource(R.plurals.page_count, state.pages, state.pages),
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            InfoText(stringResource(R.string.favored_times, state.favoriteCount), Modifier.weight(1f))
            InfoText(state.posted.orEmpty(), Modifier.weight(1f))
        }
        if (!state.size.isNullOrEmpty()) {
            Row {
                InfoText(state.size.orEmpty(), Modifier.weight(1f))
            }
        }
        Text(
            text = stringResource(R.string.more_information),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun ActionsRow(
    state: GalleryDetailUiState,
    onFavoriteClick: () -> Unit,
    onFavoriteLongClick: () -> Unit,
    onShareClick: () -> Unit,
    onTorrentClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onSimilarClick: () -> Unit,
    onRateClick: () -> Unit,
    onNewerVersionClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (state.hasNewerVersions) {
            Text(
                text = stringResource(R.string.newer_version_avaliable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onNewerVersionClick, onLongClick = {})
                    .padding(vertical = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.combinedClickable(
                    onClick = onFavoriteClick,
                    onLongClick = onFavoriteLongClick,
                ),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.favorited || state.localFavorites) {
                            R.drawable.v_heart_x24
                        } else {
                            R.drawable.v_heart_broken_x24
                        },
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = when {
                        state.localFavorites -> stringResource(R.string.local_favorites)
                        state.favorited -> state.favoriteName ?: stringResource(R.string.favorited)
                        else -> stringResource(R.string.not_favorited)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            ActionText(stringResource(R.string.share), onShareClick)
            ActionText(stringResource(R.string.torrent_count, state.torrentCount), onTorrentClick)
            ActionText(stringResource(R.string.archive), onArchiveClick)
            ActionText(stringResource(R.string.similar_gallery), onSimilarClick)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onRateClick, onLongClick = {})
                .padding(vertical = 4.dp),
        ) {
            RatingBar(rating = state.rating)
            Text(
                text = if (state.ratingCount > 0) {
                    stringResource(R.string.rating_text, state.rating, state.ratingCount)
                } else {
                    stringResource(R.string.rating_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ActionText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = {}),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsBlock(
    state: GalleryDetailUiState,
    onTagClick: (TagUi) -> Unit,
    onTagLongClick: (TagUi) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (state.tags.isEmpty()) {
            Text(
                text = stringResource(R.string.no_tags),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.tags.forEach { group ->
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    group.tags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            onClick = { onTagClick(tag) },
                            onLongClick = { onTagLongClick(tag) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TagChip(tag: TagUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Text(
            text = tag.display,
            style = MaterialTheme.typography.labelMedium,
            fontStyle = if (tag.locked) FontStyle.Italic else null,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(
                alpha = if (tag.weak) 0.5f else 1f,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CommentsBlock(
    state: GalleryDetailUiState,
    onCommentsClick: () -> Unit,
    onCommentClick: (CommentUi) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCommentsClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (state.comments.isEmpty()) {
            Text(
                text = stringResource(R.string.no_comments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        state.comments.take(2).forEach { comment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = comment.user,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = comment.timeAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val linkColor = MaterialTheme.colorScheme.primary
            val annotated = remember(comment.content, linkColor) {
                comment.content?.toAnnotatedString(SpanStyle(color = linkColor))
                    ?: AnnotatedString("")
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onCommentClick(comment) }, onLongClick = {}),
            )
        }
        Text(
            text = if (state.commentsTotal <= 2) {
                stringResource(R.string.no_more_comments)
            } else {
                stringResource(R.string.more_comment)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewsBlock(
    state: GalleryDetailUiState,
    onPreviewClick: (Int) -> Unit,
    onPreviewsMoreClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPreviewsMoreClick, onLongClick = {})
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (state.previews.isEmpty()) {
            Text(
                text = stringResource(R.string.no_previews),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val context = LocalContext.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cellWidthPx = Settings.previewSize.coerceAtLeast(1)
            val columns = (constraints.maxWidth / cellWidthPx).coerceAtLeast(1)
            val cellWidth = maxWidth / columns
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.previews.forEach { preview ->
                    Column(
                        modifier = Modifier
                            .width(cellWidth - 4.dp)
                            .combinedClickable(
                                onClick = { onPreviewClick(preview.index) },
                                onLongClick = {},
                            ),
                    ) {
                        val request = remember(preview.imageKey, preview.imageUrl) {
                            ImageRequest.Builder(context)
                                .data(preview.imageUrl)
                                .apply {
                                    memoryCacheKey(preview.imageKey)
                                    diskCacheKey(preview.imageKey)
                                }
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = (preview.position + 1).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = if (state.previewPages <= 1 && state.previewNum >= 0) {
                stringResource(R.string.no_more_previews)
            } else {
                stringResource(R.string.more_previews)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TagMenuDialog(
    tag: TagUi,
    loggedIn: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShowDefinition: () -> Unit,
    onAddFilter: () -> Unit,
    onVote: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tag.tag) },
        text = {
            Column {
                MenuRow(stringResource(android.R.string.copy), onCopy)
                MenuRow(stringResource(R.string.show_definition), onShowDefinition)
                MenuRow(stringResource(R.string.add_filter), onAddFilter)
                if (loggedIn) {
                    if (tag.status != TagStatus.UP) {
                        MenuRow(
                            text = stringResource(
                                if (tag.status == TagStatus.DOWN) {
                                    R.string.tag_vote_down_cancel
                                } else {
                                    R.string.tag_vote_up
                                },
                            ),
                            onClick = { onVote(1) },
                        )
                    }
                    if (tag.status != TagStatus.DOWN) {
                        MenuRow(
                            text = stringResource(
                                if (tag.status == TagStatus.UP) {
                                    R.string.tag_vote_up_cancel
                                } else {
                                    R.string.tag_vote_down
                                },
                            ),
                            onClick = { onVote(-1) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun downloadText(state: GalleryDetailUiState): String = when {
    state.downloadState == DownloadInfo.STATE_INVALID -> stringResource(R.string.download)
    state.downloadState == DownloadInfo.STATE_FINISH && state.hasNewerVersions ->
        stringResource(R.string.download_upgradeable)
    state.downloadState == DownloadInfo.STATE_NONE -> stringResource(R.string.download_state_none)
    state.downloadState == DownloadInfo.STATE_WAIT -> stringResource(R.string.download_state_wait)
    state.downloadState == DownloadInfo.STATE_DOWNLOAD -> stringResource(R.string.download_state_downloading)
    state.downloadState == DownloadInfo.STATE_FINISH -> stringResource(R.string.download_state_downloaded)
    else -> stringResource(R.string.download_state_failed)
}
