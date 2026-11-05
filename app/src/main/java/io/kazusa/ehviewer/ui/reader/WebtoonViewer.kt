package io.kazusa.ehviewer.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.collectAsState
import io.kazusa.ehviewer.gallery.Page
import io.kazusa.ehviewer.gallery.PageLoader
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.NavigationRegions
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import io.kazusa.ehviewer.tachiyomi.ui.reader.viewer.getAction
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * 条漫：一整条 `LazyColumn`，缩放挂成列表的**祖先**（挂在兄弟节点会吃掉命中测试）。
 *
 * [withGaps] 为 false 是「连续纵向」模式，页与页之间不留缝。
 */
@Composable
fun WebtoonViewer(
    lazyListState: LazyListState,
    withGaps: Boolean,
    pageLoader: PageLoader,
    navigator: () -> NavigationRegions,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val items = pageLoader.pages
    val zoomableState = rememberZoomableState(zoomSpec = WebtoonZoomSpec)
    val density = LocalDensity.current
    val paddingPercent by Settings.webtoonSidePadding.collectAsState()
    val sidePadding by remember(density) {
        snapshotFlow {
            with(density) {
                (lazyListState.layoutInfo.viewportSize.width * paddingPercent / 100f).toDp()
            }
        }
    }.collectAsState(0.dp)
    LazyColumn(
        modifier = modifier.zoomable(
            state = zoomableState,
            onClick = { offset ->
                scope.launch {
                    with(lazyListState) {
                        val (w, h) = layoutInfo.viewportSize
                        val (x, y) = offset
                        when (navigator().getAction(Offset(x / w, y / h))) {
                            NavigationRegion.MENU -> onMenuRegionClick()
                            NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                            NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
                        }
                    }
                }
            },
            onLongClick = { ofs ->
                val info = lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                    info.offset <= ofs.y && info.offset + info.size > ofs.y
                }
                if (info != null) onSelectPage(items[info.index])
            },
            onDoubleClick = DoubleTapZoom,
        ),
        state = lazyListState,
        contentPadding = PaddingValues(horizontal = sidePadding),
        verticalArrangement = Arrangement.spacedBy(if (withGaps) 15.dp else 0.dp),
    ) {
        items(items, key = { it.index }) { page ->
            PagerItem(
                page = page,
                pageLoader = pageLoader,
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private val WebtoonZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 3f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)
