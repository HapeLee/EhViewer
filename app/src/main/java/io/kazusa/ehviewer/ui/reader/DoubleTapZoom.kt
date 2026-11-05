package io.kazusa.ehviewer.ui.reader

import androidx.compose.ui.geometry.Offset
import io.kazusa.ehviewer.Settings
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomableState

/** 双击：已放大就复位，否则在双击点放大一倍 */
object DoubleTapZoom : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        if (!Settings.doubleTapToZoom.value) return
        val zoomFraction = state.zoomFraction ?: return // 内容还没就绪
        if (zoomFraction > 0.05f) {
            state.resetZoom()
        } else {
            // workaround: https://github.com/saket/telephoto/issues/45
            state.zoomTo(
                zoomFactor = state.contentTransformation.scaleMetadata.initialScale.scaleX * 2f,
                centroid = centroid,
            )
        }
    }
}
