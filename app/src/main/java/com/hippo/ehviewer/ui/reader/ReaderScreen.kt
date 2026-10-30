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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.request.ImageRequest
import java.io.File
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.coil3.coil
import me.saket.telephoto.zoomable.rememberZoomableImageState

/**
 * Compose 阅读器：横向翻页 + 单页缩放/平移。
 *
 * 分块解码由 telephoto 的 `SubSamplingImageSource` 负责（配合 Coil），
 * 因此不再需要自绘 GL 与 `nativeTexImage` 那套 512×512 纹理上传。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    pageSource: PageSource,
    initialIndex: Int = 0,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { pageSource.count }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${pagerState.currentPage + 1} / ${pageSource.count}") },
                navigationIcon = {
                    // 不引入 material-icons 依赖，先用字符箭头
                    IconButton(onClick = onBack) {
                        Text("\u2190")
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { index ->
            PageContent(index = index, pageSource = pageSource)
        }
    }
}

@Composable
private fun PageContent(index: Int, pageSource: PageSource) {
    val context = LocalPlatformContext.current
    val imageLoader = context.imageLoader
    var file by remember(index) { mutableStateOf<File?>(null) }
    var failure by remember(index) { mutableStateOf<String?>(null) }

    LaunchedEffect(index) {
        runCatching { pageSource.page(index) }
            .onSuccess { file = it }
            .onFailure { failure = it.message ?: it.javaClass.simpleName }
    }

    val current = file
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failure == null) {
                CircularProgressIndicator()
            } else {
                Text(failure!!)
            }
        }
        return
    }

    val request = remember(current) {
        ImageRequest.Builder(context).data(current).build()
    }
    val zoomState = rememberZoomableImageState()
    ZoomableImage(
        image = ZoomableImageSource.coil(request, imageLoader),
        contentDescription = pageSource.name(index),
        state = zoomState,
        gestures = EnabledZoomGestures.ZoomAndPan,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}
