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
package io.kazusa.ehviewer.ui.compose.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 一行的点击行为，替代原实现里靠下标判断的 `INDEX_URL` / `INDEX_PARENT` */
enum class InfoAction {
    /** 复制到剪贴板 */
    COPY,

    /** 复制并记住哈希，避免被识别成需要搜索的画廊链接 */
    COPY_AND_REMEMBER,

    /** 用浏览器打开（父画廊） */
    OPEN_URL,
}

data class InfoRow(
    val label: String,
    val value: String?,
    val action: InfoAction? = null,
)

/**
 * 画廊信息页（原 `scene_gallery_info.xml` + `GalleryInfoScene`）。
 *
 * 原来的实现用一个 `RecyclerView` + 两张 item 布局拼出「表头 + 键值对」，
 * 现在直接交给 `LazyColumn`；行行为从「按下标猜」改成每行自带 [InfoAction]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryInfoScreen(
    title: String,
    rows: List<InfoRow>,
    onBack: () -> Unit,
    onCopy: (value: String, remember: Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(rows, key = { it.label }) { row ->
                InfoRowItem(
                    row = row,
                    onCopy = onCopy,
                    onOpenUrl = onOpenUrl,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun InfoRowItem(
    row: InfoRow,
    onCopy: (String, Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val value = row.value
    val action = row.action
    val modifier = if (value != null && action != null) {
        Modifier.clickable {
            when (action) {
                InfoAction.COPY -> onCopy(value, false)
                InfoAction.COPY_AND_REMEMBER -> onCopy(value, true)
                InfoAction.OPEN_URL -> onOpenUrl(value)
            }
        }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
