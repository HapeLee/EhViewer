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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings

/**
 * 阅读器的首次引导，Compose 版 `widget/GalleryGuideView`。
 *
 * 两步：先说「左右 1/3 翻页、中间上半菜单、中间下半进度条」，再说「长按开页面菜单」。
 * 任意一步点一下推进，第二步点完写 `Settings.putGuideGallery(false)`，之后不再出现。
 */
@Composable
fun ReaderGuide(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxSize()
            // 整屏都是「下一步」的热区，与旧 View 一致（它也是点自身推进）
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
            .clickable {
                if (step == 0) {
                    step = 1
                } else {
                    Settings.putGuideGallery(false)
                    onFinish()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (step == 0) {
            Row(Modifier.fillMaxSize()) {
                GuideCell(
                    text = stringResource(R.string.guide_gallery_left),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    GuideCell(
                        text = stringResource(R.string.guide_gallery_menu),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    GuideCell(
                        text = stringResource(R.string.guide_gallery_progress),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                GuideCell(
                    text = stringResource(R.string.guide_gallery_right),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            GuideCell(
                text = stringResource(R.string.guide_gallery_long_click),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GuideCell(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
