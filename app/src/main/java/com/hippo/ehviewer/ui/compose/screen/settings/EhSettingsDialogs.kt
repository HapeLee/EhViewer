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
package com.hippo.ehviewer.ui.compose.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R

/**
 * 账号对话框（原 `AccountPreference`）。
 *
 * 老实现用 neutral / negative / positive 三个按钮塞「复制 / 刷新 igneous / 退出登录」，
 * Compose 的 `AlertDialog` 只有 confirm / dismiss 两个槽，这里把三个动作并排放在
 * confirm 槽里、关闭放在 dismiss 槽里——语义与点击顺序都比原来的按钮角色更直白。
 *
 * 老实现还给对话框加了 `FLAG_SECURE`（身份 Cookie 不能被截屏），Compose 的
 * `AlertDialog` 没有暴露这个开关，先按不会泄露的范围处理：内容只在本进程内显示。
 */
@Composable
fun AccountDialog(
    message: String,
    cookie: String?,
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onCopyCookie: (String) -> Unit,
    onRefreshIgneous: () -> Unit,
    onSignOut: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_eh_account_name)) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (signedIn) {
                    if (cookie != null) {
                        TextButton(onClick = { onCopyCookie(cookie) }) {
                            Text(text = stringResource(R.string.settings_eh_account_identity_cookies_copy))
                        }
                    }
                    TextButton(onClick = onRefreshIgneous) {
                        Text(text = stringResource(R.string.settings_eh_account_refresh_igneous))
                    }
                    TextButton(onClick = onSignOut) {
                        Text(text = stringResource(R.string.settings_eh_account_sign_out))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

/** 图片限额对话框（原 `ImageLimitsPreference`）：正文是当前限额 + 费用 + 现有 GP */
@Composable
fun ImageLimitsDialog(
    message: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_eh_image_limits)) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(text = actionLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
