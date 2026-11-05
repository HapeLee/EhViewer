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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.R

/** 登录失败提示：`canIgnore` 为真时（Cloudflare 拦截）额外给一个「忽略」出口 */
data class CookieSignInError(
    val message: String,
    val canIgnore: Boolean,
)

/**
 * Cookie 登录页（原 `scene_cookie_sign_in.xml` + `CookieSignInScene`）。
 *
 * 迁移点：
 * - `TextInputLayout` + `TextInputEditText` 换成 `OutlinedTextField`，错误文案沿用 [ErrorText]
 * - 进度遮罩、`from_clipboard` 链接、Cloudflare 时的「忽略」按钮都保留
 */
@Composable
fun CookieSignInScreen(
    memberId: String,
    passHash: String,
    memberIdError: String?,
    passHashError: String?,
    loading: Boolean,
    error: CookieSignInError?,
    onMemberIdChange: (String) -> Unit,
    onPassHashChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFillFromClipboard: () -> Unit,
    onDismissError: () -> Unit,
    onOpenForums: () -> Unit,
    onIgnoreError: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.v_cookie_brown_x48),
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                text = stringResource(R.string.cookie_explain),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            )
            OutlinedTextField(
                value = memberId,
                onValueChange = onMemberIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ipb_member_id") },
                singleLine = true,
                isError = memberIdError != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            ErrorText(memberIdError)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = passHash,
                onValueChange = onPassHashChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ipb_pass_hash") },
                singleLine = true,
                isError = passHashError != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
            ErrorText(passHashError)
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onSubmit,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
            TextButton(onClick = onFillFromClipboard, enabled = !loading) {
                Text(stringResource(R.string.from_clipboard))
            }
        }

        if (loading) {
            // 遮罩 + 拦截点击，等价原来的 progress FrameLayout
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }

    error?.let {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.sign_in_failed)) },
            text = { Text(it.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissError()
                        onOpenForums()
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = if (it.canIgnore) {
                {
                    TextButton(onClick = onIgnoreError) {
                        Text(stringResource(R.string.ignore))
                    }
                }
            } else {
                null
            },
        )
    }
}
