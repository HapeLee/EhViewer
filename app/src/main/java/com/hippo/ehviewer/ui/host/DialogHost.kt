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
package com.hippo.ehviewer.ui.host

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hippo.ehviewer.ui.compose.component.DialogListCheckItem
import com.hippo.ehviewer.ui.compose.component.DialogListItem
import com.hippo.ehviewer.ui.compose.component.DialogListItemSpacing

/**
 * 宿主级对话框请求。
 *
 * 这是「命令式弹框」的替代：`CommonOperations` 之类的工具层拿不到 Compose 上下文，
 * 就把「要弹什么」投给宿主（[EhHost.showDialog]），由 [DialogHost] 渲染。
 * 文案一律是已解析的字符串——请求是在非 Compose 环境里构造的。
 *
 * 每个请求都带 `onCancel`：点外部、按返回键时触发（对应旧的 `setOnCancelListener`）。
 * 点按钮后先关对话框再回调，回调里可以安全地再投递一个新请求。
 */
sealed interface DialogRequest {
    val title: String?

    /**
     * 输入框（可带一个勾选框）。替代 `EditTextDialogBuilder` 与
     * `EditTextCheckBoxDialogBuilder`。
     */
    data class TextInput(
        override val title: String?,
        val hint: String?,
        val initialText: String,
        val positiveText: String,
        /** 输入框上方的正文（WebView 的 js prompt 用），可为空 */
        val message: String? = null,
        val checkText: String? = null,
        val initialChecked: Boolean = false,
        val onConfirm: (text: String, checked: Boolean) -> Unit,
        val onCancel: () -> Unit,
    ) : DialogRequest

    /**
     * 单选列表（可带一个勾选框）。替代 `ListCheckBoxDialogBuilder`——
     * 它的语义是「点中一项就完成」，勾选框状态随选项一起提交。
     */
    data class ListSingleChoice(
        override val title: String?,
        val items: List<String>,
        val checkText: String? = null,
        val initialChecked: Boolean = false,
        val onSelect: (index: Int, checked: Boolean) -> Unit,
        val onCancel: () -> Unit,
    ) : DialogRequest

    /**
     * 一段话 + 一到三个按钮。替代散在各处的 `AlertDialog.Builder().setMessage(...)`，
     * 以及 WebView 的 js `alert` / `confirm`。
     *
     * 第三个按钮（[neutralText]）是给「打开设置 / 取消 / 不再显示」这类三元选择用的，
     * M3 的 `AlertDialog` 只有确认与取消两个槽位，所以 [MessageRequestDialog] 自己排按钮。
     */
    data class Message(
        override val title: String?,
        val message: String,
        val positiveText: String,
        val negativeText: String? = null,
        val neutralText: String? = null,
        /** 富文本正文（`Spanned`，带可点链接）；非空时优先于 [message] */
        val richMessage: CharSequence? = null,
        val onPositive: () -> Unit,
        val onNegative: () -> Unit = {},
        val onNeutral: () -> Unit = {},
        val onCancel: () -> Unit = {},
    ) : DialogRequest
}

/** 渲染当前请求；`onDismiss` 只负责把宿主的请求状态置空 */
@Composable
fun DialogHost(request: DialogRequest, onDismiss: () -> Unit) {
    when (request) {
        is DialogRequest.TextInput -> TextInputRequestDialog(request, onDismiss)
        is DialogRequest.ListSingleChoice -> ListSingleChoiceRequestDialog(request, onDismiss)
        is DialogRequest.Message -> MessageRequestDialog(request, onDismiss)
    }
}

/**
 * 一段话 + 一到三个按钮。
 *
 * `AlertDialog` 只有 confirm / dismiss 两个槽，第三个动作（「不再显示」）按
 * `EhSettingsDialogs.AccountDialog` 的既有做法并排进 confirm 槽：
 * 渲染出来是 `[取消] [不再显示] [打开设置]`，语义与点击顺序都比硬塞进正文更直白。
 */
@Composable
private fun MessageRequestDialog(request: DialogRequest.Message, onDismiss: () -> Unit) {
    fun cancel() {
        onDismiss()
        request.onCancel()
    }

    AlertDialog(
        onDismissRequest = ::cancel,
        title = request.title?.let { title -> { Text(text = title) } },
        text = {
            val rich = request.richMessage
            if (rich != null) RichMessage(rich) else MessageText(request.message)
        },
        confirmButton = {
            // 次要动作按 negative → neutral 排在左侧，确认在最右
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                request.negativeText?.let { text ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            request.onNegative()
                        },
                    ) {
                        Text(text = text)
                    }
                }
                request.neutralText?.let { text ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            request.onNeutral()
                        },
                    ) {
                        Text(text = text)
                    }
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        request.onPositive()
                    },
                ) {
                    Text(text = request.positiveText)
                }
            }
        },
        dismissButton = null,
    )
}

@Composable
private fun MessageText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 富文本正文：公告类内容是从 HTML 转来的 `Spanned`，Compose 没有等价渲染，
 * 只能让原生 `TextView` 现算一次（样式仍取当前 M3 的正文 token）。
 */
@Composable
private fun RichMessage(rich: CharSequence) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // `a` 标签生成的 URLSpan 用的是 TextView 的 linkColor，默认取自 XML 主题的
    // `textColorLink`（M3 基线蓝），这里跟着色板走
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textSize = MaterialTheme.typography.bodyMedium.fontSize
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                includeFontPadding = false
            }
        },
        update = { view ->
            view.text = rich
            view.setTextColor(color.toArgb())
            view.setLinkTextColor(linkColor)
            view.textSize = textSize.value
        },
    )
}

@Composable
private fun TextInputRequestDialog(request: DialogRequest.TextInput, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(request.initialText) }
    var checked by rememberSaveable { mutableStateOf(request.initialChecked) }
    val cancelText = stringResource(android.R.string.cancel)

    fun cancel() {
        onDismiss()
        request.onCancel()
    }

    AlertDialog(
        onDismissRequest = ::cancel,
        title = request.title?.let { title -> { Text(text = title) } },
        text = {
            Column {
                request.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = request.hint?.let { hint -> { Text(text = hint) } },
                    singleLine = true,
                )
                val checkText = request.checkText
                if (checkText != null) {
                    DialogListCheckItem(
                        text = checkText,
                        checked = checked,
                        onToggle = { checked = !checked },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    request.onConfirm(text, checked)
                },
            ) {
                Text(text = request.positiveText)
            }
        },
        dismissButton = {
            TextButton(onClick = ::cancel) {
                Text(text = cancelText)
            }
        },
    )
}

@Composable
private fun ListSingleChoiceRequestDialog(request: DialogRequest.ListSingleChoice, onDismiss: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(request.initialChecked) }
    val cancelText = stringResource(android.R.string.cancel)

    fun cancel() {
        onDismiss()
        request.onCancel()
    }

    AlertDialog(
        onDismissRequest = ::cancel,
        title = request.title?.let { title -> { Text(text = title) } },
        text = {
            Column {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
                ) {
                    itemsIndexed(request.items) { index, label ->
                        DialogListItem(
                            text = label,
                            onClick = {
                                onDismiss()
                                request.onSelect(index, checked)
                            },
                        )
                    }
                }
                val checkText = request.checkText
                if (checkText != null) {
                    DialogListCheckItem(
                        text = checkText,
                        checked = checked,
                        onToggle = { checked = !checked },
                        // 与列表之间比项间距多留一点，免得和最后一项粘在一起
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = ::cancel) {
                Text(text = cancelText)
            }
        },
    )
}
