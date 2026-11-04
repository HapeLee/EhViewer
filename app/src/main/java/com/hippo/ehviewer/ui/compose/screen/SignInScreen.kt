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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.hippo.ehviewer.R

/**
 * 输入框下方的错误文案，对应原来 `TextInputLayout` 的 `error`。
 */
@Composable
internal fun ErrorText(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    )
}

/**
 * 登录页（原 `scene_login.xml` + `SignInScene`）。
 *
 * 迁移点：
 * - 两个 `TextInputLayout` 换成 `OutlinedTextField`，错误文案走 `supportingText` + `isError`
 * - 密码可见性开关用自绘 eye 图标（原来靠 `passwordToggleEnabled`）
 * - 显示中的请求仍由 Scene 侧的协程驱动，这里只表达 `loading` / `errorMessage` 两个状态
 */
@Composable
fun SignInScreen(
    username: String,
    password: String,
    usernameError: String?,
    passwordError: String?,
    loading: Boolean,
    errorMessage: String?,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onRegister: () -> Unit,
    onSignInViaWebView: () -> Unit,
    onSignInViaCookies: () -> Unit,
    onSkipSignIn: () -> Unit,
    onDismissError: () -> Unit,
    onOpenForums: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }
    // R.mipmap.ic_launcher 在 API 26+ 是 <adaptive-icon>，painterResource 只认 vector / 位图，
    // 直接传会抛 IllegalArgumentException，所以先栅格化成 BitmapPainter
    val context = LocalContext.current
    val logoPainter = remember(context) {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.let { drawable ->
            val size = drawable.intrinsicWidth.takeIf { it > 0 } ?: 144
            BitmapPainter(drawable.toBitmap(size, size).asImageBitmap())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                logoPainter?.let { painter ->
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(24.dp)
                            .size(96.dp),
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    isError = usernameError != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
                    ),
                )
                ErrorText(usernameError)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    isError = passwordError != null,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSignIn() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = stringResource(R.string.password),
                            )
                        }
                    },
                )
                ErrorText(passwordError)
                // 这一页没有 Scaffold / Surface 铺底，虽然 `EhTheme` 已经把 LocalContentColor
                // 兜到 onBackground，这里仍显式写出「窗口正文色」，避免将来主题层再变时又退回黑色
                Text(
                    text = stringResource(R.string.app_waring),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSignIn,
                    enabled = !loading,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sign_in))
                }
                OutlinedButton(
                    onClick = onRegister,
                    enabled = !loading,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.register))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onSignInViaWebView, enabled = !loading) {
                        Text(stringResource(R.string.sign_in_via_webview))
                    }
                    TextButton(onClick = onSignInViaCookies, enabled = !loading) {
                        Text(stringResource(R.string.sign_in_via_cookies))
                    }
                }
                TextButton(
                    onClick = onSkipSignIn,
                    enabled = !loading,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.tourist_mode))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (loading) {
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

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.sign_in_failed)) },
            text = {
                Text(
                    text = errorMessage + "\n\n" + stringResource(R.string.sign_in_failed_tip),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissError()
                        onOpenForums()
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
