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

import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.hippo.ehviewer.R

/**
 * 站内 WebView 编辑器（UConfig / 我的标签）的界面部分。
 *
 * 和 [com.hippo.ehviewer.ui.compose.screen.SignInWebViewScreen] 同一套做法：WebView 由场景
 * 构造（要装 client、cookie、QUIC hints），这里只负责挂到 Compose 树、加载进度条，以及
 * UConfig 特有的「应用」动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebEditorScreen(
    @StringRes titleRes: Int,
    progress: Int,
    onBack: () -> Unit,
    onApply: (() -> Unit)?,
    createWebView: () -> WebView,
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (onApply != null) {
                        TextButton(onClick = onApply) {
                            Text(text = stringResource(R.string.apply))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { createWebView() },
                modifier = Modifier.fillMaxSize(),
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
