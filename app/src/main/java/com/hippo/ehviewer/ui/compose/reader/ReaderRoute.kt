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
 * EhViewer is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.ui.compose.reader

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.SourcePageLoader
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.reader.PageSource
import com.hippo.ehviewer.ui.reader.ReaderPageAction
import com.hippo.ehviewer.ui.reader.ReaderScreen
import com.hippo.ehviewer.ui.reader.copyImage
import com.hippo.ehviewer.ui.reader.copyToTemp
import com.hippo.ehviewer.ui.reader.galleryUrlOrNull
import com.hippo.ehviewer.ui.reader.readerCacheDir
import com.hippo.ehviewer.ui.reader.readerCopyTempDir
import com.hippo.ehviewer.ui.reader.readerPageSource
import com.hippo.ehviewer.ui.reader.readerTempDir
import com.hippo.ehviewer.ui.reader.saveImageToAlbum
import com.hippo.ehviewer.ui.reader.shareImage
import com.hippo.ehviewer.ui.reader.writeToUri
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ReaderPrepare {
    Preparing,
    Ready,
    Failed,
}

/**
 * 阅读器入口：造页源 → 等它就绪 → 交给 [ReaderScreen]。
 *
 * 页源的生命周期挂在这里而不是屏幕里：`PageSource` 背后是 `SpiderQueen` 与一份临时
 * 缓存目录，离开这一页就要释放，跟 UI 的重组无关。EHz 那一版的 `PageLoader` 结构
 * 由 `SourcePageLoader` 桥接，取字节仍走本仓这套页源。
 */
@Composable
fun ReaderRoute(route: EhRoute.Reader) {
    val navigator = ehNavigator()
    val context = LocalContext.current
    // 压缩包没有 gid，用 uri 做缓存目录的键
    val cacheKey = remember(route) { if (route.gid != -1L) "${route.gid}" else "archive-${route.uri?.hashCode()}" }
    val cacheDir = remember(cacheKey) { readerCacheDir(context, cacheKey) }
    // 压缩包要密码时由开包流程发信号，UI 弹框把结果喂回去（null = 用户放弃）
    val passwdFlow = remember(route) { MutableSharedFlow<String?>(extraBufferCapacity = 1) }
    val passwordRequested = remember(route) { MutableStateFlow(false) }
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveToLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) {
            file.delete()
        } else {
            scope.launch { context.writeToUri(file, uri) }
        }
    }
    var pageSource by remember(route) { mutableStateOf<PageSource?>(null) }
    var preparing by remember(route) { mutableStateOf(ReaderPrepare.Preparing) }
    var startPage by remember(route) { mutableIntStateOf(-1) }
    // 入口 route 只有 gid，页源也拿不到标题：历史表是唯一兜底，查不到就空串（顶栏退回页码）
    var title by remember(route) { mutableStateOf("") }

    DisposableEffect(route) {
        onDispose { pageSource?.close() }
    }
    LaunchedEffect(route) {
        val source = runCatching {
            readerPageSource(route, cacheDir, context, passwdFlow) { passwordRequested.value = it }
        }.getOrNull()
        if (source == null) {
            preparing = ReaderPrepare.Failed
            return@LaunchedEffect
        }
        pageSource = source
        if (!runCatching { source.prepare() && source.count > 0 }.getOrDefault(false)) {
            preparing = ReaderPrepare.Failed
            return@LaunchedEffect
        }
        // 入口带的页码 > 数据库里记的进度 > 页源自己记的起始页
        startPage = route.page.takeIf { it >= 0 }
            ?: withContext(Dispatchers.IO) { EhDB.getReadingProgress(route.gid) }?.takeIf { it < source.count }
            ?: source.startPage
        // 历史表是唯一「按 gid 就能查到标题」的地方；查不到再退到页源自己的标题
        title = withContext(Dispatchers.IO) { EhDB.getGalleryTitle(route.gid) }.orEmpty()
            .ifBlank { source.title }
        preparing = ReaderPrepare.Ready
    }

    val source = pageSource
    val needPassword by passwordRequested.collectAsState()
    when (preparing) {
        ReaderPrepare.Preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ReaderPrepare.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.error_reading_failed))
        }
        ReaderPrepare.Ready -> if (source != null && startPage >= 0) {
            // 键必须是页源实例：旋转后旧页源已经关了，但 ViewModelStore / remember 里
            // 可能还留着旧对象，不打键会拿到「拿着已关闭页源」的那一份
            val loader = remember(source, startPage) {
                SourcePageLoader(scope, source, startPage, route.gid, title)
            }
            DisposableEffect(loader) {
                onDispose { loader.close() }
            }
            ReaderScreen(
                pageLoader = loader,
                // 退出前 close 会按 startPage 落一次进度，然后才真正退出
                onBack = {
                    loader.close()
                    navigator.pop()
                },
                onPageAction = { action, index ->
                    handlePageAction(context, scope, loader, route, action, index, saveToLauncher) { pendingSave = it }
                },
            )
        }
    }

    if (needPassword) {
        ArchivePasswordDialog(
            onConfirm = { passwdFlow.tryEmit(it) },
            onDismiss = { passwdFlow.tryEmit(null) },
        )
    }
}

/**
 * 页面菜单的动作落到 Android 侧。
 *
 * 放在这一层而不是 `ReaderScreen`：`rememberLauncherForActivityResult` 要的是
 * Activity 级别的注册，而且「另存为」得先把文件准备好再起 SAF，属于副作用链。
 */
private fun handlePageAction(
    context: Context,
    scope: CoroutineScope,
    loader: SourcePageLoader,
    route: EhRoute.Reader,
    action: ReaderPageAction,
    index: Int,
    saveToLauncher: ManagedActivityResultLauncher<String, Uri?>,
    onPendingSave: (File) -> Unit,
) {
    fun toast(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    when (action) {
        ReaderPageAction.Refresh -> loader.retryPage(index)
        ReaderPageAction.Share -> scope.launch {
            val source = loader.file(index) ?: return@launch toast(R.string.error_cant_save_image)
            val temp = copyToTemp(source, readerTempDir(), source.name)
                ?: return@launch toast(R.string.error_cant_create_temp_file)
            context.shareImage(temp, galleryUrlOrNull(route.gid, route.token))
        }
        ReaderPageAction.Copy -> scope.launch {
            val source = loader.file(index) ?: return@launch toast(R.string.error_cant_save_image)
            val temp = copyToTemp(source, readerCopyTempDir(), source.name)
                ?: return@launch toast(R.string.error_cant_create_temp_file)
            context.copyImage(temp)
        }
        ReaderPageAction.Save -> scope.launch {
            val source = loader.file(index) ?: return@launch toast(R.string.error_cant_save_image)
            context.saveImageToAlbum(source, source.name)
        }
        ReaderPageAction.SaveTo, ReaderPageAction.DownloadOriginal -> scope.launch {
            val original = action == ReaderPageAction.DownloadOriginal
            val dir = readerTempDir() ?: return@launch toast(R.string.error_cant_create_temp_file)
            val file = if (original) {
                loader.file(index, original = true)
            } else {
                loader.file(index)?.let { copyToTemp(it, dir, it.name) }
            }
            if (file == null) return@launch toast(R.string.error_cant_save_image)
            onPendingSave(file)
            saveToLauncher.launch(file.name)
        }
    }
}
