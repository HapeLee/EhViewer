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
package com.hippo.ehviewer.ui.compose.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.reader.PageSource
import com.hippo.ehviewer.ui.reader.ReaderIntent
import com.hippo.ehviewer.ui.reader.ReaderPageAction
import com.hippo.ehviewer.ui.reader.ReaderScreen
import com.hippo.ehviewer.ui.reader.ReaderSettings
import com.hippo.ehviewer.ui.reader.ReaderTheme
import com.hippo.ehviewer.ui.reader.ReaderViewModel
import com.hippo.ehviewer.ui.reader.copyImage
import com.hippo.ehviewer.ui.reader.copyToTemp
import com.hippo.ehviewer.ui.reader.galleryUrlOrNull
import com.hippo.ehviewer.ui.reader.readerCopyTempDir
import com.hippo.ehviewer.ui.reader.readerPageSource
import com.hippo.ehviewer.ui.reader.readerSettings
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

/**
 * lint 的 `ContextCastToActivity` 让改用 `LocalActivity`，但当前 activity-compose 版本还没有它
 * （`androidx.activity.compose.LocalActivity` 和 `androidx.compose.ui.platform.LocalActivity`
 * 都 unresolved）。把转换挪进普通函数：语义不变，但 lint 看到的不再是
 * 「LocalContext.current 被 cast 成 Activity」。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Window 级设置：屏幕方向、系统栏、屏幕常亮、亮度。
 *
 * 按第 7 节的纪律放在这一层而不是 [ReaderScreen] 里——它们改的是 Activity 的
 * `Window`，离开阅读器必须还原，跟组合的生命周期绑定会漏。
 */
@Composable
private fun ReaderWindowEffects(settings: ReaderSettings) {
    // 转不成功就什么都不做——这些只是 Window 效果，比强行兜底安全
    val activity = LocalContext.current.findActivity() ?: return

    DisposableEffect(activity, settings.screenRotation) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = when (settings.screenRotation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity.requestedOrientation = previous }
    }

    DisposableEffect(activity, settings.fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (settings.fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(activity, settings.keepScreenOn) {
        val window = activity.window
        val wasOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            // 只在「进来之前是关的」时候才清，免得把别人的常亮需求一起关掉
            if (!wasOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(activity, settings.customScreenLightness, settings.screenLightness) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            // <= 100 的部分由 ReaderScreen 的遮罩负责，这里只处理「加亮」
            screenBrightness = if (settings.customScreenLightness && settings.screenLightness > 100) {
                ((settings.screenLightness - 100) / 100f).coerceAtLeast(0.01f)
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
}

private enum class ReaderPrepare {
    Preparing,
    Ready,
    Failed,
}

/**
 * 阅读器入口：只负责「造页源 → 等它就绪 → 交给 [ReaderScreen]」。
 *
 * 页源的生命周期挂在这里而不是 [ReaderScreen] 里：`PageSource` 背后是
 * `SpiderQueen` 与一份临时缓存目录，离开这一页就要释放，跟 UI 的重组无关。
 * 亮度和全屏这类 Window 级操作同理，后面几片也放这一层。
 */
@Composable
fun ReaderRoute(route: EhRoute.Reader) {
    val navigator = ehNavigator()
    val context = LocalContext.current
    // 压缩包没有 gid，用 uri 做缓存目录的键
    val cacheKey = remember(route) { if (route.gid != -1L) "${route.gid}" else "archive-${route.uri?.hashCode()}" }
    val cacheDir = remember(cacheKey) { File(context.cacheDir, "reader/$cacheKey") }
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
            val vm = viewModel { ReaderViewModel(source, startPage, readerSettings(), route.gid) }
            ReaderWindowEffects(vm.uiState.settings)
            // read_theme：在子树里换色板就够了，不需要 recreate Activity
            ReaderTheme(vm.uiState.settings.readTheme) {
                ReaderScreen(
                    uiState = vm.uiState,
                    onIntent = vm::onIntent,
                    onPageAction = { action, index ->
                        handlePageAction(context, scope, vm, route, action, index, saveToLauncher) { pendingSave = it }
                    },
                    onBack = { navigator.pop() },
                )
            }
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
    vm: ReaderViewModel,
    route: EhRoute.Reader,
    action: ReaderPageAction,
    index: Int,
    saveToLauncher: ManagedActivityResultLauncher<String, Uri?>,
    onPendingSave: (File) -> Unit,
) {
    fun toast(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    when (action) {
        ReaderPageAction.Refresh -> vm.onIntent(ReaderIntent.Refresh(index))
        ReaderPageAction.Share -> scope.launch {
            val source = vm.file(index) ?: return@launch toast(R.string.error_cant_save_image)
            val temp = copyToTemp(source, readerTempDir(), source.name)
                ?: return@launch toast(R.string.error_cant_create_temp_file)
            context.shareImage(temp, galleryUrlOrNull(route.gid, route.token))
        }
        ReaderPageAction.Copy -> scope.launch {
            val source = vm.file(index) ?: return@launch toast(R.string.error_cant_save_image)
            val temp = copyToTemp(source, readerCopyTempDir(), source.name)
                ?: return@launch toast(R.string.error_cant_create_temp_file)
            context.copyImage(temp)
        }
        ReaderPageAction.Save -> scope.launch {
            val source = vm.file(index) ?: return@launch toast(R.string.error_cant_save_image)
            context.saveImageToAlbum(source, source.name)
        }
        ReaderPageAction.SaveTo, ReaderPageAction.DownloadOriginal -> scope.launch {
            val original = action == ReaderPageAction.DownloadOriginal
            val dir = readerTempDir() ?: return@launch toast(R.string.error_cant_create_temp_file)
            val file = if (original) {
                vm.original(index, dir)
            } else {
                vm.file(index)?.let { copyToTemp(it, dir, it.name) }
            }
            if (file == null) return@launch toast(R.string.error_cant_save_image)
            onPendingSave(file)
            saveToLauncher.launch(file.name)
        }
    }
}
