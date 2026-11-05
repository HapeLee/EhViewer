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
package io.kazusa.ehviewer.ui.compose.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhUtils
import io.kazusa.ehviewer.client.data.GalleryInfo
import io.kazusa.ehviewer.client.getThumbKey
import io.kazusa.ehviewer.client.thumbUrl
import io.kazusa.ehviewer.download.DownloadManager
import kotlin.math.roundToInt

/**
 * 画廊条目布局。取值与旧 `GalleryAdapter.TYPE_LIST` / `TYPE_GRID`、
 * `Settings.listMode` 完全一致，因此不需要再做一层映射。
 */
object GalleryItemLayout {
    const val LIST = 0
    const val GRID = 1
}

/** 列表态缩略图高度，对应 `dimen/gallery_list_thumb_height` 的观感 */
private val ListThumbHeight: Dp = 96.dp

/** 网格态单列最小宽度，对应旧 `Settings.thumbSize` 的默认量级 */
val GridCellMinWidth: Dp = 108.dp

/**
 * 画廊条目（列表 / 网格两种形态）。
 *
 * 这是从旧的 `item_gallery_list.xml` + `item_gallery_grid.xml` + `GalleryAdapter`
 * 提炼出来的共享组件：收藏页与画廊列表页都用它，避免出现两套卡片样式。
 *
 * 收藏角标与下载角标直接读 [GalleryInfo.favoriteSlot] 和 [DownloadManager] 的内存表，
 * 不再由调用方逐个透传；状态变化时由调用方自增一个版本号触发重组（与旧
 * `notifyDataSetChanged()` 等价）。
 */
@Composable
fun GalleryItem(
    info: GalleryInfo,
    layout: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favourited = info.favoriteSlot in -1..9
    val downloaded = remember(info.gid) { DownloadManager.containDownloadInfo(info.gid) }
    when (layout) {
        GalleryItemLayout.GRID -> GalleryGridItem(
            info = info,
            selected = selected,
            selectionMode = selectionMode,
            favourited = favourited,
            downloaded = downloaded,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
        )
        else -> GalleryListItem(
            info = info,
            selected = selected,
            selectionMode = selectionMode,
            favourited = favourited,
            downloaded = downloaded,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun cardColors(selected: Boolean) = CardDefaults.cardColors(
    containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    },
)

@Composable
private fun cardBorder(selected: Boolean) = if (selected) {
    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
} else {
    null
}

@Composable
private fun GalleryListItem(
    info: GalleryInfo,
    selected: Boolean,
    selectionMode: Boolean,
    favourited: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = cardColors(selected),
        border = cardBorder(selected),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
        ) {
            GalleryThumb(
                info = info,
                modifier = Modifier
                    .height(ListThumbHeight)
                    .aspectRatio(2f / 3f),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(ListThumbHeight)
                    .padding(start = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = EhUtils.getSuitableTitle(info),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = if (Settings.listTitleSingleLine) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectionMode && selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = info.uploader.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.disowned) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RatingBar(rating = info.rating)
                    if (Settings.showGalleryPages && info.pages > 0) {
                        Text(
                            text = "${info.pages}P",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                CategoryBadge(category = info.category)
            }
            Column(
                modifier = Modifier
                    .height(ListThumbHeight)
                    .padding(start = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (favourited || downloaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (favourited) {
                            StatusIcon(Icons.Filled.Favorite)
                        }
                        if (downloaded) {
                            StatusIcon(Icons.Filled.Download)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val language = info.simpleLanguage
                if (!language.isNullOrEmpty()) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = info.posted.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GalleryGridItem(
    info: GalleryInfo,
    selected: Boolean,
    selectionMode: Boolean,
    favourited: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = cardColors(selected),
        border = cardBorder(selected),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Box {
                GalleryThumb(
                    info = info,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                    corner = 0.dp,
                )
                if (favourited || downloaded) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (favourited) {
                            StatusIcon(Icons.Filled.Favorite)
                        }
                        if (downloaded) {
                            StatusIcon(Icons.Filled.Download)
                        }
                    }
                }
                if (selectionMode && selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CategoryBadge(
                    category = info.category,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                )
            }
            if (Settings.thumbShowTitle) {
                Text(
                    text = EhUtils.getSuitableTitle(info),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GalleryThumb(
    info: GalleryInfo,
    modifier: Modifier = Modifier,
    corner: Dp = 4.dp,
) {
    val context = LocalPlatformContext.current
    val thumbKey = getThumbKey(info.gid)
    val thumbUrl = info.thumbUrl
    val request = remember(thumbKey, thumbUrl) {
        ImageRequest.Builder(context)
            .data(thumbUrl)
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
        modifier = modifier.clip(RoundedCornerShape(corner)),
    )
}

@Composable
private fun StatusIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 5 星评分，支持半星；与旧 `item_gallery_list.xml` 的 5 个 ImageView 等价 */
@Composable
fun RatingBar(
    rating: Float,
    starSize: Dp = 12.dp,
) {
    val tenths = (rating * 2).roundToInt().coerceIn(0, 10)
    val full = tenths / 2
    val hasHalf = tenths % 2 == 1
    val starTint = colorResource(R.color.yellow_800)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            val icon = when {
                index < full -> Icons.Filled.Star
                index == full && hasHalf -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarOutline
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = starTint,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** 分类色块 */
@Composable
fun CategoryBadge(
    category: Int,
    modifier: Modifier = Modifier,
) {
    if (category == EhUtils.UNKNOWN) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(EhUtils.getCategoryColor(category)))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = EhUtils.getCategory(category).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}
