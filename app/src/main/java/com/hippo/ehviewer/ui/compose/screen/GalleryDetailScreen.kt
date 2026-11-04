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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.ui.compose.component.CategoryBadge
import com.hippo.ehviewer.ui.compose.component.DialogListItem
import com.hippo.ehviewer.ui.compose.component.DialogListItemSpacing
import com.hippo.ehviewer.ui.compose.component.EhPullToRefreshBox
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

/** 只有真正拿到内容（含头部局部刷新）的阶段才给阅读入口 */
private fun hasDetailContent(phase: Int): Boolean = phase != DetailPhase.INIT && phase != DetailPhase.REFRESH && phase != DetailPhase.FAILED

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
 * 版式参考漫画详情页的常见做法，按 EH 的数据（没有章节表、简介也很少）裁剪：
 * - 头部：封面放大模糊铺底 + 渐隐到 surface，圆形封面 + 标题 / 上传者 / 分类 + 元信息徽章；
 *   返回与溢出菜单是压在头部之上的悬浮按钮，内容滚动时仍可见，不再用 `TopAppBar`
 * - 动作条：收藏 / 下载 / 评分 / 种子 四个图标按钮（分享收进顶栏溢出菜单），
 *   右侧 `ExtendedFloatingActionButton` 是阅读入口（有进度时显示「从第 N 页读起」）
 * - 内容区是一列圆角卡片（标签 / 评论 / 预览 / 信息），替代原来的分割线堆叠；
 *   因为 EH 没有目录，也不做「介绍 / 目录 / 评论」的 tab
 * - `ViewTransition` 的多态改成 `when (phase)`：整页 loading / 头部 + 局部 loading / 内容 / 失败重试
 * - 归档 / 种子 / 评分三个对话框仍由 Scene 用原有 XML 布局实现，这里只提供入口与回调
 */
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

    // 交给 Scaffold：容器色 / 内容色（contentColor 默认取 onBackground，没写颜色的 Text
    // 因此不会再退回 Color.Black）和 FAB 的摆放都由它负责
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // 头部要顶到状态栏后面，insets 由各块自己处理，这里不再统一扣一遍
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (hasDetailContent(state.phase)) {
                ReadFab(
                    text = if (state.startPage >= 0) {
                        stringResource(R.string.read_from, state.startPage + 1)
                    } else {
                        stringResource(R.string.read)
                    },
                    onClick = onReadClick,
                    // 左右 / 底部的 16dp 由 Scaffold 摆 FAB 时提供，这里只补手势条的 inset
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
        ) {
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
                // 详情页原来的「刷新」在溢出菜单里，改成下拉刷新；`REFRESH_HEADER` 就是
                // 「正文还在、正在重新拉头部」的那个阶段，正好对应 isRefreshing
                else -> EhPullToRefreshBox(
                    isRefreshing = state.phase == DetailPhase.REFRESH_HEADER,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        DetailHero(
                            state = state,
                            onUploaderClick = onUploaderClick,
                            onUploaderLongClick = onUploaderLongClick,
                            onCategoryClick = onCategoryClick,
                        )
                        // 刷新时不再插一条居中的转圈：下拉指示器顶在头部上方，内容原地保留
                        // 比整块换成 spinner 更稳（也避免 hero 高度跳变）
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (state.hasNewerVersions) {
                                NewerVersionBanner(onNewerVersionClick)
                            }
                            ActionGrid(
                                state = state,
                                downloadText = downloadText(state),
                                onFavoriteClick = onFavoriteClick,
                                onFavoriteLongClick = onFavoriteLongClick,
                                onDownloadClick = onDownloadClick,
                                onDownloadLongClick = onDownloadLongClick,
                                onRateClick = onRateClick,
                                onTorrentClick = onTorrentClick,
                            )
                            TagsCard(state, onTagClick) { tagMenuTarget = it }
                            CommentsCard(state, onCommentsClick, onCommentClick)
                            InfoCard(
                                state = state,
                                onInfoClick = onInfoClick,
                                onRateClick = onRateClick,
                                onTorrentClick = onTorrentClick,
                                onArchiveClick = onArchiveClick,
                                onSimilarClick = onSimilarClick,
                            )
                            PreviewsCard(state, onPreviewClick, onPreviewsMoreClick)
                            // 给右下角的「阅读」悬浮按钮让位
                            Spacer(Modifier.height(96.dp))
                        }
                    }
                }
            }

            // 顶栏浮在头部之上且不随内容滚走：头部被滚出屏幕后仍然能返回 / 开溢出菜单
            DetailTopBar(
                onBack = onBack,
                overflowVisible = overflowVisible,
                onOverflowVisibleChange = { overflowVisible = it },
                onAddTag = onAddTag,
                onClearImageCache = onClearImageCache,
                onShare = onShareClick,
                onOpenInOtherApp = onOpenInOtherApp,
            )
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

/** 头部：模糊封面铺底 + 圆形封面 + 标题 / 上传者 / 分类 / 元信息徽章 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHero(
    state: GalleryDetailUiState,
    onUploaderClick: () -> Unit,
    onUploaderLongClick: () -> Unit,
    onCategoryClick: () -> Unit,
) {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.background
    val coverKey = remember(state.gid) { "preview:large:${state.gid}:0" }
    val backdropKey = remember(state.gid) { "preview:blur:${state.gid}" }
    val cover = remember(coverKey, state.thumbUrl) {
        ImageRequest.Builder(context)
            .data(state.thumbUrl)
            .apply {
                memoryCacheKey(coverKey)
                diskCacheKey(coverKey)
            }
            .crossfade(true)
            .build()
    }
    val backdrop = remember(backdropKey, state.thumbUrl) {
        ImageRequest.Builder(context)
            .data(state.thumbUrl)
            .apply {
                memoryCacheKey(backdropKey)
                diskCacheKey(backdropKey)
            }
            .crossfade(true)
            .build()
    }

    // clipToBounds 是必须的：下面那张放大 1.2 倍的模糊图不会被 scale 裁剪，
    // 不裁的话会在头部下方溢出成一条彩色横带，压在卡片两侧露出来
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        if (state.thumbUrl.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
        } else {
            // 放大 1.2 倍再模糊，免得模糊半径把边缘吃掉露出空底
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .scale(1.2f)
                    .blur(40.dp)
                    .graphicsLayer { alpha = 0.45f },
            )
        }
        // 向下渐隐到页面底色：头部和下面的卡片区之间不能留色差
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to background.copy(alpha = 0.25f),
                        0.7f to background.copy(alpha = 0.65f),
                        0.92f to background.copy(alpha = 0.94f),
                        1f to background,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                // 上方留出悬浮顶栏的位置
                .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 20.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(105.dp)
                        .aspectRatio(3f / 4f)
                        .shadow(6.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!state.uploader.isNullOrEmpty()) {
                        Text(
                            text = state.uploader,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (state.disowned) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.combinedClickable(
                                onClick = onUploaderClick,
                                onLongClick = onUploaderLongClick,
                            ),
                        )
                    }
                    CategoryBadge(
                        category = state.category,
                        modifier = Modifier.combinedClickable(onClick = onCategoryClick, onLongClick = {}),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiniBadge("${stringResource(R.string.key_gid)} ${state.gid}")
                if (state.pages > 0) {
                    MiniBadge(pluralStringResource(R.plurals.page_count, state.pages, state.pages))
                }
                if (!state.language.isNullOrEmpty()) {
                    MiniBadge(state.language)
                }
            }
        }
    }
}

@Composable
private fun MiniBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 顶栏圆形按钮的配色：底色取 surface（不用默认的 secondaryContainer），图标取 onSurface */
@Composable
private fun topBarButtonColors(): IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
)

/** 悬浮顶栏：返回 + 溢出菜单，压在头部之上，不随内容滚动 */
@Composable
private fun BoxScope.DetailTopBar(
    onBack: () -> Unit,
    overflowVisible: Boolean,
    onOverflowVisibleChange: (Boolean) -> Unit,
    onAddTag: () -> Unit,
    onClearImageCache: () -> Unit,
    onShare: () -> Unit,
    onOpenInOtherApp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            // 这里的 12 = 内容区的 16 - 4：FilledTonalIconButton 的触控区是 48dp、
            // 视觉圆只有 40dp，左右各多出 4dp，只有把 padding 减掉这 4dp 才能和卡片左边缘对齐
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            // 底色用 surface 而不是默认的 secondaryContainer，图标用 onSurface 才够对比
            colors = topBarButtonColors(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            FilledTonalIconButton(
                onClick = { onOverflowVisibleChange(true) },
                colors = topBarButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                )
            }
            DropdownMenu(
                expanded = overflowVisible,
                onDismissRequest = { onOverflowVisibleChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_tag)) },
                    onClick = {
                        onOverflowVisibleChange(false)
                        onAddTag()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_clear_image_cache)) },
                    onClick = {
                        onOverflowVisibleChange(false)
                        onClearImageCache()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = {
                        onOverflowVisibleChange(false)
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_in_other_app)) },
                    onClick = {
                        onOverflowVisibleChange(false)
                        onOpenInOtherApp()
                    },
                )
            }
        }
    }
}

/** 右下角悬浮的阅读入口：直接用 M3 的 ExtendedFloatingActionButton，样式不自定义 */
@Composable
private fun ReadFab(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
            )
        },
        text = {
            Text(
                text = text,
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun NewerVersionBanner(onClick: () -> Unit) {
    Surface(
        shape = DetailCardShape,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {}),
    ) {
        Text(
            text = stringResource(R.string.newer_version_avaliable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** 卡片统一圆角，和参考设计一致 */
private val DetailCardShape = RoundedCornerShape(14.dp)

/** 预览图每列的最小宽度：363dp 宽的手机上一行 3 张，平板 / 横屏自动加列 */
private val PreviewCellMinWidth = 96.dp
private val PreviewCellSpacing = 4.dp

@Composable
private fun DetailCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DetailCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** 四个动作：收藏 / 下载 / 评分 / 种子，样式对齐参考设计的图标 + 文字竖排的动作条 */
@Composable
private fun ActionGrid(
    state: GalleryDetailUiState,
    downloadText: String,
    onFavoriteClick: () -> Unit,
    onFavoriteLongClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDownloadLongClick: () -> Unit,
    onRateClick: () -> Unit,
    onTorrentClick: () -> Unit,
) {
    Surface(
        shape = DetailCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val favorited = state.favorited || state.localFavorites
            DetailActionItem(
                modifier = Modifier.weight(1f),
                icon = rememberVectorPainter(if (favorited) Icons.Filled.Favorite else Icons.Filled.HeartBroken),
                label = state.favoriteCount.toString(),
                tint = if (favorited) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = onFavoriteClick,
                onLongClick = onFavoriteLongClick,
            )
            ActionDivider()
            DetailActionItem(
                modifier = Modifier.weight(1f),
                icon = rememberVectorPainter(Icons.Filled.Download),
                label = downloadText,
                onClick = onDownloadClick,
                onLongClick = onDownloadLongClick,
            )
            ActionDivider()
            DetailActionItem(
                modifier = Modifier.weight(1f),
                icon = rememberVectorPainter(Icons.Filled.Star),
                label = stringResource(R.string.rate),
                onClick = onRateClick,
            )
            ActionDivider()
            DetailActionItem(
                modifier = Modifier.weight(1f),
                icon = rememberVectorPainter(Icons.Filled.Link),
                label = stringResource(R.string.torrent_count, state.torrentCount),
                onClick = onTorrentClick,
            )
        }
    }
}

@Composable
private fun ActionDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    )
}

@Composable
private fun DetailActionItem(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoCard(
    state: GalleryDetailUiState,
    onInfoClick: () -> Unit,
    onRateClick: () -> Unit,
    onTorrentClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onSimilarClick: () -> Unit,
) {
    DetailCard {
        CardTitle(stringResource(R.string.gallery_info))
        // 评分行：星星 + 档位文字 + 分数与人数，点一下直接评分
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onRateClick, onLongClick = {})
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RatingBar(rating = state.rating)
            Text(
                text = if (state.ratingCount > 0) {
                    stringResource(
                        R.string.rating_text,
                        stringResource(ratingTextRes(state.rating)),
                        state.rating,
                        state.ratingCount,
                    )
                } else {
                    stringResource(R.string.rating_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        SpecRow(stringResource(R.string.key_language), state.language.orEmpty())
        SpecRow(
            label = stringResource(R.string.key_pages),
            value = pluralStringResource(R.plurals.page_count, state.pages, state.pages),
        )
        if (!state.posted.isNullOrEmpty()) {
            SpecRow(stringResource(R.string.key_posted), state.posted)
        }
        if (!state.size.isNullOrEmpty()) {
            SpecRow(stringResource(R.string.key_size), state.size)
        }
        SpecRow(stringResource(R.string.key_favorite_count), state.favoriteCount.toString())
        SpecRow(
            label = stringResource(R.string.key_favorited),
            value = when {
                state.localFavorites -> stringResource(R.string.local_favorites)
                state.favorited -> state.favoriteName ?: stringResource(R.string.favorited)
                else -> stringResource(R.string.not_favorited)
            },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CardLink(stringResource(R.string.more_information), onInfoClick)
            CardLink(stringResource(R.string.torrent_count, state.torrentCount), onTorrentClick)
            CardLink(stringResource(R.string.archive), onArchiveClick)
            CardLink(stringResource(R.string.similar_gallery), onSimilarClick)
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun CardLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsCard(
    state: GalleryDetailUiState,
    onTagClick: (TagUi) -> Unit,
    onTagLongClick: (TagUi) -> Unit,
) {
    DetailCard {
        CardTitle(stringResource(R.string.tags))
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
        }
    }
}

@Composable
private fun TagChip(tag: TagUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Text(
            text = "# ${tag.display}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontStyle = if (tag.locked) FontStyle.Italic else null,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (tag.weak) 0.5f else 1f,
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CommentsCard(
    state: GalleryDetailUiState,
    onCommentsClick: () -> Unit,
    onCommentClick: (CommentUi) -> Unit,
) {
    DetailCard {
        CardTitle(stringResource(R.string.gallery_comments))
        if (state.comments.isEmpty()) {
            Text(
                text = stringResource(R.string.no_comments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.comments.take(2).forEach { comment ->
                Row(modifier = Modifier.fillMaxWidth()) {
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
                    lineHeight = 22.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onCommentClick(comment) }, onLongClick = {}),
                )
            }
        }
        // 卡片本身不再是点击热区，入口固定留在这里，空评论也能进评论页
        CardLink(
            text = if (state.commentsTotal <= 2) {
                stringResource(R.string.no_more_comments)
            } else {
                stringResource(R.string.more_comment)
            },
            onClick = onCommentsClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewsCard(
    state: GalleryDetailUiState,
    onPreviewClick: (Int) -> Unit,
    onPreviewsMoreClick: () -> Unit,
) {
    DetailCard {
        CardTitle(stringResource(R.string.gallery_previews))
        if (state.previews.isEmpty()) {
            Text(
                text = stringResource(R.string.no_previews),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PreviewsGrid(state, onPreviewClick)
        }
        CardLink(
            text = if (state.previewPages <= 1 && state.previewNum >= 0) {
                stringResource(R.string.no_more_previews)
            } else {
                stringResource(R.string.more_previews)
            },
            onClick = onPreviewsMoreClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewsGrid(state: GalleryDetailUiState, onPreviewClick: (Int) -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 不再用设置里的固定像素宽度，改按「每列至少 PreviewCellMinWidth」反推列数
        val columns = ((maxWidth + PreviewCellSpacing) / (PreviewCellMinWidth + PreviewCellSpacing))
            .toInt()
            .coerceAtLeast(2)
        val cellWidth = (maxWidth - PreviewCellSpacing * (columns - 1)) / columns
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PreviewCellSpacing),
            verticalArrangement = Arrangement.spacedBy(PreviewCellSpacing),
        ) {
            state.previews.forEach { preview ->
                Column(
                    modifier = Modifier
                        .width(cellWidth)
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
            Column(verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing)) {
                DialogListItem(text = stringResource(android.R.string.copy), onClick = onCopy)
                DialogListItem(text = stringResource(R.string.show_definition), onClick = onShowDefinition)
                DialogListItem(text = stringResource(R.string.add_filter), onClick = onAddFilter)
                if (loggedIn) {
                    if (tag.status != TagStatus.UP) {
                        DialogListItem(
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
                        DialogListItem(
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
