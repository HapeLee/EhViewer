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
package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.hippo.ehviewer.R

/**
 * 长按某一页弹出来的操作。
 *
 * 与旧 `GalleryActivity.showPageDialog` 一一对应，只是把「刷新」也留在列表里
 * （旧实现是 `removeCache + forceRequest`，这边是 `PageSource.invalidate`）。
 */
enum class ReaderPageAction {
    Refresh,
    Share,
    Copy,
    Save,
    SaveTo,
    DownloadOriginal,
}

/**
 * @param hasOriginal 只有在线画廊才有「下载原图」这一项（旧实现的
 *   `ACTION_EH == mAction && !Settings.getDownloadOriginImage(false)`）
 */
@Composable
fun ReaderPageMenu(
    index: Int,
    hasOriginal: Boolean,
    onAction: (ReaderPageAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.page_menu_title, index + 1)) },
        text = {
            val actions = buildList {
                add(ReaderPageAction.Refresh)
                add(ReaderPageAction.Share)
                add(ReaderPageAction.Copy)
                add(ReaderPageAction.Save)
                add(ReaderPageAction.SaveTo)
                if (hasOriginal) add(ReaderPageAction.DownloadOriginal)
            }
            Column {
                actions.forEach { action ->
                    TextButton(
                        onClick = { onAction(action) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(labelOf(action)))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun labelOf(action: ReaderPageAction): Int = when (action) {
    ReaderPageAction.Refresh -> R.string.page_menu_refresh
    ReaderPageAction.Share -> R.string.page_menu_share
    ReaderPageAction.Copy -> android.R.string.copy
    ReaderPageAction.Save -> R.string.page_menu_save
    ReaderPageAction.SaveTo -> R.string.page_menu_save_to
    ReaderPageAction.DownloadOriginal -> R.string.page_menu_download_original
}
