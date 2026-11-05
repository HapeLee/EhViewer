package io.kazusa.ehviewer.tachiyomi.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.R

private val animationSpec = tween<IntOffset>(200)

/**
 * 上下两条浮栏：顶栏是标题 + 返回，底栏是进度滑块 + 功能按钮。
 *
 * 与 EHz 一致，两条各自从屏幕边缘滑入滑出；容器是 `BoxScope`，所以直接 `align`。
 */
@Composable
fun BoxScope.ReaderAppBars(
    visible: Boolean,
    title: String,
    isRtl: Boolean,
    showSeekBar: Boolean,
    currentPage: Int,
    totalPages: Int,
    onSliderValueChange: (Int) -> Unit,
    onClickSettings: () -> Unit,
    onBack: () -> Unit,
) {
    // 跟随阅读器自己的明暗（下面包了 EhTheme），不要看系统主题：黑底阅读器配浅色栏才对
    val backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal))
            .align(Alignment.TopStart),
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = animationSpec),
        exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = animationSpec),
    ) {
        TopAppBar(
            // 拿不到标题（没有历史记录）时退回页码，免得顶栏空着
            title = { Text(text = title.ifBlank { "$currentPage / $totalPages" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(R.drawable.ic_reader_back), contentDescription = null)
                }
            },
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal))
            .align(Alignment.BottomStart),
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = animationSpec),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = animationSpec),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSeekBar && totalPages > 1) {
                ChapterNavigator(
                    isRtl = isRtl,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onSliderValueChange = onSliderValueChange,
                    containerColor = backgroundColor,
                )
            }
            BottomReaderBar(onClickSettings = onClickSettings, containerColor = backgroundColor)
        }
    }
}
