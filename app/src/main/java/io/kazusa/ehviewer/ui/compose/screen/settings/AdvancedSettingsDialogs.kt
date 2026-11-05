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
package io.kazusa.ehviewer.ui.compose.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.EhProxySelector
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.ui.compose.component.DialogListItemSpacing
import io.kazusa.ehviewer.ui.compose.component.DialogRadioItem

/**
 * 代理对话框（原 `ProxyPreference`）。
 *
 * 类型三选一，HTTP / SOCKS 必须填 ip 与端口，其余两种忽略这两项（置灰而不是隐藏，
 * 与老对话框里「填了也不算」的实际效果一致）。校验口径没变：
 * 空 ip / 空端口报 `text_is_empty`，端口不是合法数字报 `proxy_invalid_port`。
 */
@Composable
fun ProxyDialog(
    typeLabels: List<String>,
    initialType: Int,
    initialIp: String?,
    initialPort: Int,
    isValidPort: (Int) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (type: Int, ip: String, port: Int) -> Unit,
) {
    var type by rememberSaveable { mutableIntStateOf(initialType.coerceIn(0, typeLabels.lastIndex)) }
    var ip by rememberSaveable { mutableStateOf(initialIp.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(if (isValidPort(initialPort)) initialPort.toString() else "") }
    var ipError by rememberSaveable { mutableStateOf<Int?>(null) }
    var portError by rememberSaveable { mutableStateOf<Int?>(null) }

    val needsAddress = type == EhProxySelector.TYPE_HTTP || type == EhProxySelector.TYPE_SOCKS
    val emptyText = stringResource(R.string.text_is_empty)
    val invalidPort = stringResource(R.string.proxy_invalid_port)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_advanced_proxy)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 类型只有几项，用普通 Column：外层已经是 verticalScroll，再套 LazyColumn 会撞高度约束
                Column(verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing)) {
                    typeLabels.forEachIndexed { index, label ->
                        DialogRadioItem(
                            text = label,
                            selected = index == type,
                            onClick = { type = index },
                        )
                    }
                }
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipError = null
                    },
                    label = { Text(text = stringResource(R.string.proxy_host_or_ip)) },
                    singleLine = true,
                    enabled = needsAddress,
                    isError = ipError != null,
                    supportingText = ipError?.let { { Text(text = emptyText) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        portError = null
                    },
                    label = { Text(text = stringResource(R.string.proxy_port)) },
                    singleLine = true,
                    enabled = needsAddress,
                    isError = portError != null,
                    supportingText = portError?.let { { Text(text = if (it == ERROR_EMPTY) emptyText else invalidPort) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedIp = ip.trim()
                    val trimmedPort = port.trim()
                    if (needsAddress && trimmedIp.isEmpty()) {
                        ipError = ERROR_EMPTY
                        return@TextButton
                    }
                    val portValue = if (trimmedPort.isEmpty()) {
                        if (needsAddress) {
                            portError = ERROR_EMPTY
                            return@TextButton
                        }
                        -1
                    } else {
                        trimmedPort.toIntOrNull()?.takeIf { isValidPort(it) } ?: run {
                            portError = ERROR_INVALID
                            return@TextButton
                        }
                    }
                    onSave(type, trimmedIp, portValue)
                    onDismiss()
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

/** UA 对话框（原 `UserAgentPreference`）：留空即回落成内置的 Chrome UA */
@Composable
fun UserAgentDialog(
    initialUserAgent: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var userAgent by rememberSaveable { mutableStateOf(initialUserAgent.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_advanced_user_agent_title)) },
        text = {
            OutlinedTextField(
                value = userAgent,
                onValueChange = { userAgent = it },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(userAgent)
                    onDismiss()
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

private const val ERROR_EMPTY = 1
private const val ERROR_INVALID = 2
