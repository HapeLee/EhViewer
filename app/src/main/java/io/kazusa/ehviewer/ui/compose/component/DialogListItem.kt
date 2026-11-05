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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 对话框列表项之间 / 与勾选行之间的竖向间距 */
val DialogListItemSpacing = 4.dp

/** 列表项的圆角：与 `Card` 的默认档位一致，对话框里也不会显得像按钮 */
val ListItemShape = RoundedCornerShape(12.dp)

/**
 * 对话框 / sheet 里的列表行统一用圆角块，取代 `ListView` 时代的分割线。
 *
 * 底色分三档，保证在任何容器上都看得出形状：
 * - 对话框：M3 的容器色是 `surfaceContainerHigh`，行用低一档的 `surfaceContainerLow`；
 * - 底部 sheet：M3 的容器色已经是 `surfaceContainerLow`，行要**高一档**（[SheetListItem]）；
 * - 选中态：统一用 `secondaryContainer`。
 */
@Composable
private fun listItemColor(selected: Boolean, onSheet: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.secondaryContainer
    onSheet -> MaterialTheme.colorScheme.surfaceContainerHigh
    else -> MaterialTheme.colorScheme.surfaceContainerLow
}

@Composable
private fun ListItemSurface(
    onClick: () -> Unit,
    selected: Boolean,
    onSheet: Boolean,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ListItemShape,
        color = listItemColor(selected = selected, onSheet = onSheet),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 对话框里的动作项：圆角矩形块，可带一个前置图标（读 / 下载 / 收藏 / 移动…）。
 *
 * 点击波纹由 `Surface(onClick)` 提供。
 */
@Composable
fun DialogListItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    ListItemSurface(
        onClick = onClick,
        selected = false,
        onSheet = false,
        modifier = modifier,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 底部 sheet 里的动作项。与 [DialogListItem] 唯一的区别是底色高一档——
 * sheet 的容器本身就是 `surfaceContainerLow`，用同一档颜色圆角就看不出来了。
 */
@Composable
fun SheetListItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    ListItemSurface(
        onClick = onClick,
        selected = selected,
        onSheet = true,
        modifier = modifier,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 对话框里的单选项：圆角卡片 + 单选圆点，整块可点（圆点自己不再吃点击） */
@Composable
fun DialogRadioItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItemSurface(
        onClick = onClick,
        selected = selected,
        onSheet = false,
        modifier = modifier,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 列表末尾的勾选行（「记住收藏夹」「记住下载标签」这类）：同样是一整行的圆角卡片，
 * 整块可点，视觉上和上面的列表项连成一片。
 */
@Composable
fun DialogListCheckItem(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        shape = ListItemShape,
        color = listItemColor(selected = false, onSheet = false),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            // 上下 14dp 与 [DialogListItem] 一致，行高才能和上面的列表项对上；
            // Checkbox 用 requiredSize 压到 20dp，否则它的 48dp 触控区会把行撑高（整块本来就可点）
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.requiredSize(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
