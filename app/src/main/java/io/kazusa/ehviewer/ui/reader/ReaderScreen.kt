package io.kazusa.ehviewer.ui.reader

import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.collectAsState
import io.kazusa.ehviewer.gallery.Page
import io.kazusa.ehviewer.gallery.PageLoader
import io.kazusa.ehviewer.tachiyomi.ui.reader.PageIndicatorText
import io.kazusa.ehviewer.tachiyomi.ui.reader.ReaderAppBars
import io.kazusa.ehviewer.tachiyomi.ui.reader.ReaderContentOverlay
import io.kazusa.ehviewer.tachiyomi.ui.reader.ReaderPageSheetMeta
import io.kazusa.ehviewer.tachiyomi.ui.reader.setting.ReadingModeType
import io.kazusa.ehviewer.tachiyomi.ui.reader.setting.ReadingModeType.RIGHT_TO_LEFT
import io.kazusa.ehviewer.ui.compose.theme.EhTheme
import kotlinx.coroutines.launch

/**
 * 阅读器主屏（EHz 结构）：底色 + 翻页容器 + 两条浮栏 + 三个浮层（设置 / 页码 / 长按菜单）。
 *
 * 页码两套口径：`syncState.sliderValue` 是「现在手指停在哪」，喂顶底栏与预取；
 * 真正落盘的进度在 [PageLoader.close] 时按 `startPage` 写一次。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    pageLoader: PageLoader,
    onBack: () -> Unit,
    onPageAction: (ReaderPageAction, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val grey = ReaderGreyBackground
    val theme by Settings.readerTheme.collectAsState()
    val bgColor = when (theme) {
        0 -> Color.White
        2 -> grey
        3 -> if (dark) grey else Color.White
        else -> Color.Black
    }

    val fullscreen by Settings.fullscreen.collectAsState()
    val cutoutShort by Settings.cutoutShort.collectAsState()
    val keepScreenOn by Settings.keepScreenOnPref.collectAsState()
    val orientation by Settings.orientationMode.collectAsState()
    val brightness by Settings.customBrightness.collectAsState()
    val brightnessValue by Settings.customBrightnessValue.collectAsState()

    // 方向 / 系统栏 / 常亮 / 亮度：进来生效，离开还原
    LaunchedEffect(activity, orientation) { activity?.setOrientation(orientation) }
    DisposableEffect(activity, fullscreen) {
        val controller = activity?.window?.insetsController
        if (fullscreen) {
            controller?.hide(android.view.WindowInsets.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(android.view.WindowInsets.Type.systemBars())
        }
        onDispose { controller?.show(android.view.WindowInsets.Type.systemBars()) }
    }
    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        val wasOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (!wasOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(activity, brightness, brightnessValue) {
        // 只有开启时才动 Window 亮度；<= 0 的部分交给遮罩
        activity?.setCustomBrightnessValue(if (brightness) brightnessValue else 0)
    }
    DisposableEffect(activity) {
        onDispose { activity?.setCustomBrightnessValue(0) }
    }

    val readingMode by Settings.readingMode.collectAsState { ReadingModeType.fromPreference(it) }
    val mode = if (readingMode == ReadingModeType.DEFAULT) RIGHT_TO_LEFT else readingMode
    val isWebtoon = ReadingModeType.isWebtoon(mode)
    val showSeekbar by Settings.showReaderSeekbar.collectAsState()
    val showPageNumber by Settings.showPageNumber.collectAsState()
    val longTap by Settings.readerLongTapAction.collectAsState()
    val volumeKeysEnabled by Settings.readWithVolumeKeys.collectAsState()
    val volumeKeysInverted by Settings.readWithVolumeKeysInverted.collectAsState()
    val colorOverlayEnabled by Settings.colorFilter.collectAsState()
    val colorOverlay by Settings.colorFilterValue.collectAsState()
    val colorOverlayMode by Settings.colorFilterMode.collectAsState {
        when (it) {
            1 -> BlendMode.Multiply
            2 -> BlendMode.Screen
            3 -> BlendMode.Overlay
            4 -> BlendMode.Lighten
            5 -> BlendMode.Darken
            else -> BlendMode.SrcOver
        }
    }

    val lazyListState = rememberLazyListState(
        LazyLayoutCacheWindow(SCROLL_FRACTION, SCROLL_FRACTION),
        pageLoader.startPage,
    )
    val pagerState = rememberPagerState(pageLoader.startPage) { pageLoader.size }
    val syncState = rememberSliderPagerDoubleSyncState(lazyListState, pagerState, pageLoader)
    var appbarVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pageMenuFor by remember { mutableIntStateOf(-1) }
    var showNavigationOverlay by remember {
        mutableStateOf(Settings.showNavigationOverlayNewUser.value || Settings.showNavigationOverlayOnStart.value)
    }
    LaunchedEffect(Unit) { Settings.showNavigationOverlayNewUser.value = false }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    // 阅读器自己定明暗：白底才用浅色文字板，黑/灰底一律走深色板，
    // 否则系统是浅色主题时，黑底上的标题、页码、设置面板全是黑字
    val isDarkReader = bgColor != Color.White

    EhTheme(darkTheme = isDarkReader) {
        // 内容色由 EhTheme 统一提供（material3 1.5 的 MaterialTheme 不再提供 LocalContentColor）
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(bgColor)
                .keyEventHandler(
                    volumeKeysEnabled = { volumeKeysEnabled && !appbarVisible },
                    volumeKeysInverted = { volumeKeysInverted },
                    movePrevious = {
                        scope.launch { if (isWebtoon) lazyListState.scrollUp() else pagerState.moveToPrevious() }
                    },
                    moveNext = {
                        scope.launch { if (isWebtoon) lazyListState.scrollDown() else pagerState.moveToNext() }
                    },
                )
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            syncState.Sync(isWebtoon) { appbarVisible = false }
            val insets = if (fullscreen) {
                if (cutoutShort) WindowInsets(0.dp) else WindowInsets.displayCutout
            } else {
                WindowInsets.systemBars
            }
            GalleryPager(
                type = mode,
                pagerState = pagerState,
                lazyListState = lazyListState,
                pageLoader = pageLoader,
                showNavigationOverlay = showNavigationOverlay,
                onNavigationModeChange = { showNavigationOverlay = true },
                onSelectPage = { page: Page -> if (longTap) pageMenuFor = page.index },
                onMenuRegionClick = {
                    appbarVisible = !appbarVisible
                    showNavigationOverlay = false
                },
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(insets),
            )
            ReaderContentOverlay(
                brightness = { brightnessValue }.takeIf { brightness && brightnessValue < 0 },
                color = { colorOverlay }.takeIf { colorOverlayEnabled },
                colorBlendMode = colorOverlayMode,
            )
            if (showPageNumber && !appbarVisible) {
                PageIndicatorText(
                    currentPage = syncState.sliderValue,
                    totalPages = pageLoader.size,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
            ReaderAppBars(
                visible = appbarVisible,
                title = pageLoader.title,
                isRtl = mode == RIGHT_TO_LEFT,
                showSeekBar = showSeekbar,
                currentPage = syncState.sliderValue,
                totalPages = pageLoader.size,
                onSliderValueChange = syncState::sliderScrollTo,
                onClickSettings = { showSettings = true },
                onBack = onBack,
            )
        }

        if (showSettings) {
            var isColorFilter by remember { mutableStateOf(false) }
            val scrim by animateColorAsState(
                targetValue = if (isColorFilter) Color.Transparent else BottomSheetDefaults.ScrimColor,
                label = "ScrimColor",
            )
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = sheetState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                scrimColor = scrim,
                dragHandle = null,
            ) {
                SettingsPager(isWebtoon = isWebtoon, modifier = Modifier.fillMaxSize()) { page ->
                    isColorFilter = page == 2
                }
            }
        }

        val menuIndex = pageMenuFor
        if (menuIndex >= 0) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { pageMenuFor = -1 },
                sheetState = sheetState,
            ) {
                ReaderPageSheetMeta(
                    retry = { pageLoader.retryPage(menuIndex) },
                    retryOrigin = { pageLoader.retryPage(menuIndex, true) },
                    share = { onPageAction(ReaderPageAction.Share, menuIndex) },
                    copy = { onPageAction(ReaderPageAction.Copy, menuIndex) },
                    save = { onPageAction(ReaderPageAction.Save, menuIndex) },
                    saveTo = { onPageAction(ReaderPageAction.SaveTo, menuIndex) },
                    dismiss = { pageMenuFor = -1 },
                )
            }
        }
    }
}

/** 阅读器「灰色」底色：不放进 XML 色表，避免 View 资源与 Compose 主题两套颜色 */
private val ReaderGreyBackground = Color(0xFF1F1F1F)
