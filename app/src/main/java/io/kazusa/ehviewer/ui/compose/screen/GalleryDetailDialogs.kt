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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.parser.ArchiveParser
import io.kazusa.ehviewer.client.parser.TorrentParser
import io.kazusa.ehviewer.ui.compose.component.DialogListItem
import io.kazusa.ehviewer.ui.compose.component.DialogListItemSpacing
import kotlin.math.roundToInt

/*
 * 详情页的一次性对话框（原 `dialog_rate.xml` / `dialog_archive_list.xml` /
 * `dialog_torrent_list.xml` / `item_select_dialog.xml` 与散在场景里的 `AlertDialog.Builder`）。
 *
 * 全部是「状态驱动」：场景持有状态，这些函数只负责渲染。丢掉状态就等于关掉对话框，
 * 与旧实现「AlertDialog 活不过重建」一致。
 */

// region 评分

/** 0..5 分（半星精度）对应的文字，与旧 `GalleryRatingBar` 的 1..10 档一致 */
internal fun ratingTextRes(rating: Float): Int = when ((rating * 2).roundToInt()) {
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

@Composable
fun RateDialog(
    initialRating: Float,
    onRate: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var rating by rememberSaveable { mutableStateOf(initialRating.coerceIn(0f, 5f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.rate)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(ratingTextRes(rating)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row {
                    for (star in 1..5) {
                        // 每颗星左右各半：点左半是半星，点右半是整星（旧 GalleryRatingBar 的行为）
                        Box(modifier = Modifier.size(44.dp)) {
                            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { rating = star - 0.5f },
                                )
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { rating = star.toFloat() },
                                )
                            }
                            Icon(
                                imageVector = ratingIcon(rating, star),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(32.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRate(rating) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun ratingIcon(rating: Float, star: Int): ImageVector = when {
    rating >= star -> Icons.Filled.Star
    rating >= star - 0.5f -> Icons.Filled.StarHalf
    else -> Icons.Filled.StarOutline
}

// endregion

// region 删除下载

@Composable
fun DeleteDownloadDialog(
    message: String,
    checkText: String,
    initialChecked: Boolean,
    onConfirm: (removeImageFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by rememberSaveable { mutableStateOf(initialChecked) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.download_remove_dialog_title)) },
        text = {
            Column {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { checked = !checked }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(text = checkText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

// endregion

// region 归档 / 种子列表

/** 归档对话框的内容状态：加载中 / 已就绪（可能为空）/ 失败 */
sealed interface ArchiveListState {
    data object Loading : ArchiveListState
    data class Ready(val archives: List<ArchiveParser.Archive>, val fundsTitle: String?) : ArchiveListState
    data class Failed(val message: String) : ArchiveListState
}

@Composable
fun ArchiveListDialog(
    state: ArchiveListState,
    onSelect: (ArchiveParser.Archive) -> Unit,
    onDismiss: () -> Unit,
) {
    val fundsTitle = (state as? ArchiveListState.Ready)?.fundsTitle

    AlertDialog(
        onDismissRequest = onDismiss,
        title = fundsTitle?.let { title -> { Text(text = title) } },
        text = {
            when (state) {
                ArchiveListState.Loading -> LoadingBox()
                is ArchiveListState.Failed -> MessageBox(state.message)
                is ArchiveListState.Ready -> if (state.archives.isEmpty()) {
                    MessageBox(stringResource(R.string.no_archives))
                } else {
                    SelectionList(
                        items = state.archives.map { archive ->
                            if (archive.isHAtH) {
                                val cost = if (archive.cost == "Free") stringResource(R.string.archive_free) else archive.cost
                                "[H@H] ${archive.name} [${archive.size}] [$cost]"
                            } else {
                                val name = stringResource(
                                    if (archive.res == "org") R.string.archive_original else R.string.archive_resample,
                                )
                                val cost = if (archive.cost == "Free!") stringResource(R.string.archive_free) else archive.cost
                                "$name [${archive.size}] [$cost]"
                            }
                        },
                        onSelect = { index -> onSelect(state.archives[index]) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

/** 种子对话框的内容状态 */
sealed interface TorrentListState {
    data object Loading : TorrentListState
    data class Ready(val torrents: List<TorrentParser.Result>) : TorrentListState
    data class Failed(val message: String) : TorrentListState
}

@Composable
fun TorrentListDialog(
    state: TorrentListState,
    onSelect: (TorrentParser.Result) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            when (state) {
                TorrentListState.Loading -> LoadingBox()
                is TorrentListState.Failed -> MessageBox(state.message)
                is TorrentListState.Ready -> if (state.torrents.isEmpty()) {
                    MessageBox(stringResource(R.string.no_torrents))
                } else {
                    SelectionList(
                        items = state.torrents.map { it.format() },
                        onSelect = { index -> onSelect(state.torrents[index]) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun MessageBox(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 单选列表（替代 `ListView` + `item_select_dialog.xml`）：圆角矩形块，不用分割线 */
@Composable
private fun SelectionList(items: List<String>, onSelect: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.heightIn(max = 400.dp),
        verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
    ) {
        itemsIndexed(items) { index, label ->
            DialogListItem(text = label, onClick = { onSelect(index) })
        }
    }
}

// endregion

// region 简单确认 / 输入 / 单选

/** 「过滤上传者」这类一句话确认 */
@Composable
fun ConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

/** 「添加标签」这类单行输入；空文本就地报错，不关对话框 */
@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val emptyError = stringResource(R.string.text_is_empty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = hint) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(text = message) } },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        error = emptyError
                    } else {
                        onSubmit(trimmed)
                    }
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

/** 「有更新版本」这类纯选择列表 */
@Composable
fun ListChoiceDialog(
    title: String,
    items: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(items) { index, label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

// endregion
