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

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryComment
import com.hippo.ehviewer.ui.compose.text.clearFormatting
import com.hippo.ehviewer.ui.compose.text.toAnnotatedString
import com.hippo.ehviewer.ui.compose.text.withLink
import com.hippo.ehviewer.ui.compose.text.withSpanStyle
import com.hippo.util.ReadableTime
import com.hippo.util.TextUrl
import com.hippo.util.loadHtml
import com.hippo.yorozuya.StringUtils

/**
 * 画廊评论页（原 `scene_gallery_comments.xml` + `GalleryCommentsScene`）。
 *
 * 迁移点：
 * - `SwipeRefreshLayout` → `PullToRefreshBox`，「更多评论」与加载中变成列表末尾项
 * - 评论正文仍由 `Html.fromHtml` + `TextUrl` 生成 `Spanned`，再转成 `AnnotatedString`；
 *   链接点击由 Compose 命中后回调，不再用 `currentSpan` 在点击时反查 span
 * - 编辑器换成 `BasicTextField` + `AnnotatedString`，加粗/斜体/下划线/删除线/链接
 *   直接作用在选区样式上，提交前再用 `toSpannable().toBBCode()` 走原协议
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryCommentsScreen(
    title: String,
    comments: List<GalleryComment>,
    hasMore: Boolean,
    loadingMore: Boolean,
    refreshing: Boolean,
    composerVisible: Boolean,
    editingId: Long,
    posting: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onNewComment: () -> Unit,
    onEditComment: (GalleryComment) -> Unit,
    onVote: (GalleryComment, Int) -> Unit,
    onBlockCommenter: (GalleryComment) -> Unit,
    onCopyComment: (CharSequence) -> Unit,
    onOpenUploader: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onComposerSend: (AnnotatedString, Long) -> Unit,
    onComposerDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var menuComment by remember { mutableStateOf<GalleryComment?>(null) }
    var blockTarget by remember { mutableStateOf<GalleryComment?>(null) }
    var voteStatus by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("\u2190") }
                },
            )
        },
        floatingActionButton = {
            if (!composerVisible) {
                FloatingActionButton(onClick = onNewComment) {
                    Icon(
                        painter = painterResource(R.drawable.v_reply_dark_x24),
                        contentDescription = stringResource(R.string.edit_comment),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    if (comments.isEmpty()) {
                        item {
                            EmptyComments(modifier = Modifier.fillParentMaxHeight(0.8f))
                        }
                    }
                    items(comments) { comment ->
                        CommentRow(
                            comment = comment,
                            onOpenLink = onOpenLink,
                            onOpenUploader = onOpenUploader,
                            onClick = { menuComment = comment },
                        )
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                    if (hasMore) {
                        item {
                            MoreCommentsRow(loading = loadingMore, onClick = onLoadMore)
                        }
                    }
                }
            }
            if (composerVisible) {
                val initial = remember(editingId) {
                    comments.firstOrNull { it.id == editingId }
                        ?.let { renderComment(context, it, colors, null) }
                        ?: AnnotatedString("")
                }
                CommentComposer(
                    editingId = editingId,
                    initial = initial,
                    posting = posting,
                    onSend = { text -> onComposerSend(text, editingId) },
                    onDismiss = onComposerDismiss,
                )
            }
        }
    }

    menuComment?.let { comment ->
        CommentMenuDialog(
            comment = comment,
            onDismiss = { menuComment = null },
            onCopy = {
                onCopyComment(renderComment(context, comment, colors, null))
                menuComment = null
            },
            onBlock = {
                menuComment = null
                blockTarget = comment
            },
            onEdit = {
                menuComment = null
                onEditComment(comment)
            },
            onVote = { vote ->
                menuComment = null
                onVote(comment, vote)
            },
            onVoteStatus = {
                menuComment = null
                voteStatus = comment.voteState
            },
        )
    }

    blockTarget?.let { comment ->
        val name = comment.user.orEmpty()
        AlertDialog(
            onDismissRequest = { blockTarget = null },
            text = { Text(stringResource(R.string.filter_the_commenter, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        blockTarget = null
                        onBlockCommenter(comment)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { blockTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    voteStatus?.let { state ->
        VoteStatusDialog(voteState = state, onDismiss = { voteStatus = null })
    }
}

@Composable
private fun EmptyComments(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.big_sad_pandroid),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.no_one_comments_gallery),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CommentRow(
    comment: GalleryComment,
    onOpenLink: (String) -> Unit,
    onOpenUploader: (String) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val openLink by rememberUpdatedState(onOpenLink)
    val text = remember(comment, colors) {
        renderComment(context, comment, colors) { openLink(it) }
    }
    val user = comment.user.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (comment.uploader) {
                    stringResource(R.string.comment_user_uploader, user)
                } else {
                    user
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = user != "Anonymous") { onOpenUploader(user) },
            )
            Text(
                text = ReadableTime.getTimeAgo(comment.time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MoreCommentsRow(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else {
            Text(
                text = stringResource(R.string.click_more_comments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CommentMenuDialog(
    comment: GalleryComment,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onBlock: () -> Unit,
    onEdit: () -> Unit,
    onVote: (Int) -> Unit,
    onVoteStatus: () -> Unit,
) {
    val actions = buildList<Pair<String, () -> Unit>> {
        add(stringResource(R.string.copy_comment_text) to onCopy)
        if (!comment.uploader && !comment.editable) {
            add(stringResource(R.string.block_commenter) to onBlock)
        }
        if (comment.editable) {
            add(stringResource(R.string.edit_comment) to onEdit)
        }
        if (comment.voteUpAble) {
            val label = if (comment.voteUpEd) R.string.cancel_vote_up else R.string.vote_up
            add(stringResource(label) to { onVote(1) })
        }
        if (comment.voteDownAble) {
            val label = if (comment.voteDownEd) R.string.cancel_vote_down else R.string.vote_down
            add(stringResource(label) to { onVote(-1) })
        }
        if (!comment.voteState.isNullOrEmpty()) {
            add(stringResource(R.string.check_vote_status) to onVoteStatus)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                actions.forEach { (label, action) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                action()
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** 投票明细：`voteState` 形如 `"user1 up, user2 down"`，与原实现同解析 */
@Composable
private fun VoteStatusDialog(voteState: String, onDismiss: () -> Unit) {
    val rows = remember(voteState) {
        StringUtils.split(voteState, ',').map { raw ->
            val str = StringUtils.trim(raw)
            val index = str.lastIndexOf(' ')
            if (index < 0) {
                str to ""
            } else {
                StringUtils.trim(str.substring(0, index)) to
                    StringUtils.trim(str.substring(index + 1))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            LazyColumn {
                items(rows) { (user, vote) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = user,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = vote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun CommentComposer(
    editingId: Long,
    initial: AnnotatedString,
    posting: Boolean,
    onSend: (AnnotatedString) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(editingId, initial) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    var urlDialog by remember { mutableStateOf(false) }
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val selection = value.selection
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormatButton("B", bold = true) {
                    value = value.styled(SpanStyle(fontWeight = FontWeight.Bold))
                }
                FormatButton("I", italic = true) {
                    value = value.styled(SpanStyle(fontStyle = FontStyle.Italic))
                }
                FormatButton("U", underline = true) {
                    value = value.styled(SpanStyle(textDecoration = TextDecoration.Underline))
                }
                FormatButton("S", strike = true) {
                    value = value.styled(SpanStyle(textDecoration = TextDecoration.LineThrough))
                }
                TextButton(onClick = { urlDialog = true }) {
                    Text(stringResource(R.string.format_url))
                }
                TextButton(
                    onClick = {
                        value = value.copy(
                            annotatedString = value.annotatedString
                                .clearFormatting(selection.min, selection.max),
                        )
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.v_clear_all_dark_x24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Text("\u00d7") }
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                maxLines = 6,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (editingId != 0L) stringResource(R.string.edit_comment) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onSend(value.annotatedString) },
                    enabled = !posting && value.text.isNotBlank(),
                ) {
                    if (posting) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.v_send_dark_x24),
                            contentDescription = stringResource(R.string.edit_comment),
                        )
                    }
                }
            }
        }
    }

    if (urlDialog) {
        UrlDialog(
            onDismiss = { urlDialog = false },
            onConfirm = { url ->
                urlDialog = false
                value = value.copy(
                    annotatedString = value.annotatedString.withLink(
                        start = selection.min,
                        end = selection.max,
                        url = url,
                        style = linkStyle,
                    ),
                )
            },
        )
    }
}

@Composable
private fun FormatButton(
    label: String,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false,
    onClick: () -> Unit,
) {
    val decorations = listOfNotNull(
        if (underline) TextDecoration.Underline else null,
        if (strike) TextDecoration.LineThrough else null,
    )
    TextButton(onClick = onClick) {
        Text(
            text = label,
            style = TextStyle(
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            ),
        )
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.format_url)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (url.isNotBlank()) onConfirm(url.trim()) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun TextFieldValue.styled(style: SpanStyle): TextFieldValue = copy(
    annotatedString = annotatedString.withSpanStyle(selection.min, selection.max, style),
)

/**
 * 评论正文：与旧 `ActualCommentHolder.generateComment` 等价，
 * 依次拼上分数、最后编辑时间，再交给 `TextUrl` 把裸链接变成可点链接。
 */
private fun renderComment(
    context: Context,
    comment: GalleryComment,
    colors: ColorScheme,
    onOpenLink: ((String) -> Unit)?,
): AnnotatedString {
    val builder = SpannableStringBuilder(loadHtml(comment.comment.orEmpty()))
    val scoreColor = colors.onSurfaceVariant.toArgb()

    fun appendStyled(text: String) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        builder.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ForegroundColorSpan(scoreColor),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    if (comment.id != 0L && comment.score != 0) {
        val score = comment.score
        builder.append("  ")
        appendStyled(if (score > 0) "+$score" else score.toString())
    }
    if (comment.lastEdited != 0L) {
        builder.append("\n\n")
        appendStyled(
            context.getString(R.string.last_edited, ReadableTime.getTimeAgo(comment.lastEdited)),
        )
    }
    val spanned = TextUrl.handleTextUrl(builder) as Spanned
    return spanned.toAnnotatedString(
        linkStyle = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
        onLinkClick = onOpenLink,
    )
}
