package com.hippo.ehviewer.ui.reader

import android.graphics.drawable.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import coil3.DrawableImage
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.progressObserved
import com.hippo.ehviewer.gallery.statusObserved
import com.hippo.ehviewer.image.Image
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop

/**
 * 单页渲染：排队 / 加载 / 就绪 / 出错四态。
 *
 * 与 EHz 一致，页的状态由 [Page.statusFlow] 驱动，图片是 [Image]（引用计数 + 缓存），
 * `pin()/unpin()` 保证「界面正在画」期间缓存淘汰不会把位图 recycle 掉。
 */
@Composable
fun PagerItem(
    page: Page,
    pageLoader: PageLoader,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        pageLoader.request(page.index)
        // 页源重启后状态会被重置成 Queued，这时要再请求一次
        page.statusFlow.drop(1).collect {
            if (page.statusFlow.value == PageStatus.Queued) {
                pageLoader.request(page.index)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { pageLoader.cancelRequest(page.index) }
    }
    val defaultError = stringResource(id = R.string.decode_image_error)
    when (val state = page.statusObserved) {
        is PageStatus.Queued, is PageStatus.Loading -> {
            Box(
                modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state is PageStatus.Loading,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "progressState",
                ) { determinate ->
                    val animatedProgress by animateFloatAsState(
                        targetValue = if (state is PageStatus.Loading) state.progressObserved else 0f,
                        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "progress",
                    )
                    if (determinate) {
                        CircularWavyProgressIndicator(progress = { animatedProgress })
                    } else {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
        is PageStatus.Ready -> {
            val image = state.image
            var painter by remember { mutableStateOf<Painter?>(null) }
            LaunchedEffect(image) {
                if (image.pin()) {
                    painter = image.toPainter()
                    try {
                        awaitCancellation()
                    } finally {
                        if (image.unpin()) pageLoader.notifyPageWait(page.index)
                    }
                }
            }
            painter?.let { painter ->
                val drawable = (painter as? DrawablePainter)?.drawable
                val grayScale by Settings.grayScale.collectAsState()
                val invert by Settings.invertedColors.collectAsState()
                Image(
                    painter = remember(painter) { painter },
                    contentDescription = null,
                    modifier = Modifier
                        .then(
                            if (drawable is Animatable) {
                                Modifier.onVisibilityChanged(minDurationMs = 33, minFractionVisible = 0.5f) {
                                    drawable.setVisible(it, false)
                                }
                            } else {
                                Modifier
                            },
                        )
                        .then(contentModifier)
                        .fillMaxSize(),
                    contentScale = contentScale,
                    colorFilter = when {
                        grayScale && invert -> grayScaleAndInvertFilter
                        grayScale -> grayScaleFilter
                        invert -> invertFilter
                        else -> null
                    },
                )
            } ?: Spacer(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT))
        }
        is PageStatus.Blocked -> Spacer(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT))
        is PageStatus.Error -> {
            Box(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT)) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        // Coil 的 IOException 常常带空 message，直接 ?: 会渲染出一片空白
                        text = state.message?.takeIf { it.isNotBlank() } ?: defaultError,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Button(
                        onClick = { pageLoader.retryPage(page.index) },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(text = stringResource(id = R.string.action_retry))
                    }
                }
            }
        }
    }
}

private fun Image.toPainter(): Painter? = when (val image = innerImage) {
    is BitmapImage -> BitmapPainter(image.bitmap, intrinsicSize.toSize())
    is DrawableImage -> DrawablePainter(image.drawable)
    else -> null
}

private const val DEFAULT_ASPECT = 1 / 1.4125f

private val invertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
private val grayScaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val grayScaleAndInvertMatrix = ColorMatrix().also { mtx ->
    mtx.setToSaturation(0f)
    mtx *= invertMatrix
}

private val grayScaleFilter = ColorFilter.colorMatrix(grayScaleMatrix)
private val invertFilter = ColorFilter.colorMatrix(invertMatrix)
private val grayScaleAndInvertFilter = ColorFilter.colorMatrix(grayScaleAndInvertMatrix)
