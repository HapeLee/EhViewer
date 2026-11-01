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

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.request.ImageRequest
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.coil3.coil
import me.saket.telephoto.zoomable.rememberZoomableImageState

/** 预取窗口：前一页 + 当前页 + 后两页。策略放 UI 层，ViewModel 只管状态 */
internal fun prefetchWindow(page: Int, count: Int): List<Int> = ((page - 1)..(page + 2)).filter { it in 0 until count }

/** 页与页之间的空隙；旧实现是 `R.dimen.gallery_pager_interval`（48dp），随 GL 栈一起删了 */
private val PAGE_INTERVAL = 48.dp

/** 顶栏 / 底栏多久自动收起，与旧 `GalleryActivity` 的 header 一致 */
private const val CHROME_AUTO_HIDE_MILLIS = 3_000L

/**
 * Compose 阅读器：横向 / 竖向翻页 + 单页缩放平移。
 *
 * 分块解码由 telephoto 的 `SubSamplingImageSource` 负责（配合 Coil），
 * 因此不再需要自绘 GL 与 `nativeTexImage` 那套 512×512 纹理上传。
 *
 * 这一层不持有任何数据：条目与加载态都来自 [ReaderUiState]，交互一律走 [onIntent]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onPageAction: (ReaderPageAction, Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = uiState.count
    // 进程重建后要回到原来那一页：显式存一份，不依赖 rememberPagerState 自己有没有保存
    var savedPage by rememberSaveable { mutableIntStateOf(-1) }
    val initialPage = savedPage.takeIf { it >= 0 } ?: uiState.currentPage
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (count - 1).coerceAtLeast(0))) { count }

    // 用户滑动：回写当前页（ViewModel 顺带把进度写回页源）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            savedPage = page
            onIntent(ReaderIntent.PageSettled(page))
        }
    }
    LaunchedEffect(pagerState, count) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { page ->
            onIntent(ReaderIntent.Prefetch(prefetchWindow(page, count)))
        }
    }
    // 程序跳页：滚完带着 id 回报，VM 据此清掉 scrollRequest
    LaunchedEffect(uiState.scrollRequest) {
        val request = uiState.scrollRequest ?: return@LaunchedEffect
        pagerState.scrollToPage(request.page)
        onIntent(ReaderIntent.PageSettled(request.page, request.id))
    }
    // 自动翻页：到最后一页自然停（下一页算不出来，ScrollTo 会被钳回原地）
    val interval = uiState.settings.autoPageInterval
    LaunchedEffect(interval, uiState.currentPage, count) {
        if (interval <= 0 || count <= 1) return@LaunchedEffect
        delay(interval * 1000L)
        onIntent(ReaderIntent.ScrollTo(uiState.currentPage + 1))
    }

    var showSettings by remember { mutableStateOf(false) }
    var showPagePicker by remember { mutableStateOf(false) }
    var pageMenuFor by rememberSaveable { mutableIntStateOf(-1) }
    var showGuide by remember { mutableStateOf(Settings.guideGallery) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    // 顶栏 / 底栏自动收起：旧实现在 GalleryActivity 里用 3 秒的 handler 做同一件事
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(chromeVisible, uiState.currentPage) {
        if (!chromeVisible) return@LaunchedEffect
        delay(CHROME_AUTO_HIDE_MILLIS)
        chromeVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 阅读主题（含 read_theme 覆盖）决定底色；不铺会把 Activity 的窗口底色漏出来
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event -> onVolumeKey(uiState, event, onIntent) },
    ) {
        val pageSpacing = if (uiState.settings.showPageInterval) PAGE_INTERVAL else 0.dp
        val contentScale = contentScaleOf(uiState.settings.scaleMode)
        val alignment = alignmentOf(uiState.settings.startPosition)
        if (uiState.settings.vertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = pageSpacing,
            ) { index ->
                ReaderItem(
                    item = uiState.pages[index],
                    onIntent = onIntent,
                    contentScale = contentScale,
                    alignment = alignment,
                    onLongClick = { pageMenuFor = index },
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = uiState.settings.reverseLayout,
                pageSpacing = pageSpacing,
            ) { index ->
                ReaderItem(
                    item = uiState.pages[index],
                    onIntent = onIntent,
                    contentScale = contentScale,
                    alignment = alignment,
                    onLongClick = { pageMenuFor = index },
                )
            }
        }

        // 亮度：<= 100 靠一层遮罩压暗（旧实现是 mMaskView），> 100 交给 Window 亮度。
        // 不设 clickable / pointerInput，翻页手势照旧穿过去
        val dim = uiState.settings.dimAlpha
        if (dim > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = dim)),
            )
        }

        // 点击区：左/右 1/3 翻页，中间上半开菜单、中间下半切换栏——引导文案承诺的就是这四件事
        Box(
            Modifier.matchParentSize().pointerInputTapZones(
                vertical = uiState.settings.vertical,
                reverse = uiState.settings.reverseLayout,
            ) { zone ->
                when (zone) {
                    ReaderTapZone.Previous -> onIntent(ReaderIntent.PageStep(-1))
                    ReaderTapZone.Next -> onIntent(ReaderIntent.PageStep(1))
                    ReaderTapZone.Menu -> showSettings = true
                    ReaderTapZone.Progress -> chromeVisible = !chromeVisible
                }
            },
        )

        if (chromeVisible) {
            ReaderTopBar(
                current = uiState.currentPage,
                count = count,
                onBack = onBack,
                onPageClick = { showPagePicker = true },
                onMenuClick = { showSettings = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderBottomBar(
                current = uiState.currentPage,
                count = count,
                onPageChange = { page -> onIntent(ReaderIntent.ScrollTo(page)) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            settings = uiState.settings,
            onSettings = { onIntent(ReaderIntent.UpdateSettings(it)) },
            onDismiss = { showSettings = false },
        )
    }
    if (showPagePicker) {
        PagePickerDialog(
            count = count,
            current = uiState.currentPage,
            onConfirm = { onIntent(ReaderIntent.ScrollTo(it)) },
            onDismiss = { showPagePicker = false },
        )
    }
    if (pageMenuFor >= 0) {
        ReaderPageMenu(
            index = pageMenuFor,
            // 只有在线来源才有「原图」这一项，已下载 / 压缩包没有
            hasOriginal = uiState.online,
            onAction = { action ->
                onPageAction(action, pageMenuFor)
                pageMenuFor = -1
            },
            onDismiss = { pageMenuFor = -1 },
        )
    }
    if (showGuide) {
        ReaderGuide(onFinish = { showGuide = false })
    }
}

/**
 * 单击后回调落在哪个区，且**不消费**任何事件。
 *
 * 不能用 `clickable` / `detectTapGestures`：它们会抢走 down，telephoto 的双击缩放与
 * 分页器的拖动就都没了。这里只观察「按下后正常抬起」：
 *
 * - 拖动翻页时位置变化被 `scrollable` 消费，`waitForUpOrCancellation` 返回 null；
 * - 长按（页面菜单）超过 `longPressTimeoutMillis` 还没抬起，也判成不是单击，
 *   否则松手时会顺带翻一页；
 * - 抬起后再等一个 `doubleTapTimeoutMillis`，期间又按下就说明是双击——留给 telephoto
 *   放大，这里什么也不做。这一步是必须的：旧实现用的就是 `onSingleTapConfirmed`。
 */
private fun Modifier.pointerInputTapZones(
    vertical: Boolean,
    reverse: Boolean,
    onTap: (ReaderTapZone) -> Unit,
): Modifier = this.pointerInput(vertical, reverse) {
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(longPressTimeout) { waitForUpOrCancellation() }
            ?: return@awaitEachGesture
        val secondDown = withTimeoutOrNull(doubleTapTimeout) {
            awaitFirstDown(requireUnconsumed = false)
        }
        if (secondDown != null) {
            // 双击：把第二下的收尾吃掉就退出，缩放交给 telephoto
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        onTap(tapZoneOf(up.position.x, up.position.y, size.width, size.height, vertical, reverse))
    }
}

/** 音量键翻页；没开这个设置时返回 false，让系统照常调音量 */
private fun onVolumeKey(
    uiState: ReaderUiState,
    event: KeyEvent,
    onIntent: (ReaderIntent) -> Unit,
): Boolean {
    if (!uiState.settings.volumePage) return false
    val native = event.nativeKeyEvent
    // 只吃按下，抬起交给系统，否则音量键会一直被吞
    if (native.action != android.view.KeyEvent.ACTION_DOWN) return false
    val step = if (uiState.settings.reverseVolumePage) -1 else 1
    val direction = when (native.keyCode) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP -> step
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -step
        else -> return false
    }
    val delta = direction * uiState.settings.volumePageInterval.coerceAtLeast(1)
    onIntent(ReaderIntent.PageStep(delta))
    return true
}

@Composable
private fun ReaderItem(
    item: ReaderItemUi,
    onIntent: (ReaderIntent) -> Unit,
    contentScale: ContentScale,
    alignment: Alignment,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is ReaderItemUi.Error -> ErrorPage(
            message = item.message,
            onRetry = { onIntent(ReaderIntent.Retry(item.index)) },
            modifier = modifier,
        )
        is ReaderItemUi.Page -> when (val loadState = item.loadState) {
            ReaderPageLoadState.Queued -> LoadingPage(progress = null, modifier = modifier)
            is ReaderPageLoadState.Loading -> LoadingPage(progress = loadState.progress, modifier = modifier)
            is ReaderPageLoadState.Ready -> PageImage(
                file = loadState.file,
                name = (item.index + 1).toString(),
                contentScale = contentScale,
                alignment = alignment,
                onLongClick = onLongClick,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LoadingPage(progress: Int?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (progress == null) {
            // 服务端没给 Content-Length，只有转圈，不假装有百分比
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(0.6f),
                )
                Spacer(Modifier.height(8.dp))
                Text("$progress%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorPage(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun PageImage(
    file: File,
    name: String,
    contentScale: ContentScale,
    alignment: Alignment,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val imageLoader = context.imageLoader
    val request = remember(file) { ImageRequest.Builder(context).data(file).build() }
    ZoomableImage(
        image = ZoomableImageSource.coil(request, imageLoader),
        contentDescription = name,
        state = rememberZoomableImageState(),
        gestures = EnabledZoomGestures.ZoomAndPan,
        contentScale = contentScale,
        // 只有图片比视口小（SCALE_ORIGIN / SCALE_FIXED）时才看得出来
        alignment = alignment,
        // 长按开页面菜单；交给 telephoto 处理，它会在缩放/拖动时不误触
        onLongClick = { onLongClick() },
        modifier = modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    current: Int,
    count: Int,
    onBack: () -> Unit,
    onPageClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        // 点页码直接跳页，与旧实现「点进度文字跳页」一致
        title = { TextButton(onClick = onPageClick) { Text("${current + 1} / $count") } },
        navigationIcon = {
            // 不引入 material-icons 依赖，先用字符箭头
            IconButton(onClick = onBack) { Text("\u2190") }
        },
        actions = { IconButton(onClick = onMenuClick) { Text("\u2261") } },
        modifier = modifier,
    )
}

/** 底栏：拖动滑块跳页。拖动过程记在本地，松手才发一次 [onPageChange] */
@Composable
private fun ReaderBottomBar(
    current: Int,
    count: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by rememberSaveable { mutableFloatStateOf(-1f) }
    val last = (count - 1).coerceAtLeast(0)
    Row(
        // 栏是浮在图片上的（旧实现也是），所以自带底色，否则压不住底下的画
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${(dragging.takeIf { it >= 0f }?.roundToInt() ?: current) + 1} / $count",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = dragging.takeIf { it >= 0f } ?: current.toFloat(),
            valueRange = 0f..last.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging.takeIf { it >= 0f }?.let { onPageChange(it.roundToInt()) }
                dragging = -1f
            },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
    }
}
