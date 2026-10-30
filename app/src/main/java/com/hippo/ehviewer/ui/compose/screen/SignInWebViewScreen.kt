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

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Web 登录页（原 `WebViewSignInScene` 里那段 `WebView(requireContext())` 的界面部分）。
 *
 * 与旧实现的差别：
 * - WebView 仍由 Scene 构造（它要装 `LoginWebViewClient` / `DialogWebChromeClient` / QUIC hints），
 *   这里只负责挂到 Compose 树上，并补一个 `LinearProgressIndicator` 显示加载进度；
 * - 旧实现把 WebView 背景设成 `android.R.attr.colorBackground`，这里改成透明 + Compose 背景，
 *   跟随主题（含 Material You 动态色）。
 */
@Composable
fun SignInWebViewScreen(
    progress: Int,
    createWebView: () -> WebView,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
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
