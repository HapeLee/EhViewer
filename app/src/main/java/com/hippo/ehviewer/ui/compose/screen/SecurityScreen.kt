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
package com.hippo.ehviewer.ui.compose.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hippo.ehviewer.R
import com.hippo.widget.lockpattern.LockPatternView

/**
 * 手势锁页（原 `scene_security.xml` + `SecurityScene`）。
 *
 * 图案绘制与手势识别仍由 [LockPatternView] 负责，这里只把容器换成 Compose：
 * 原布局的 `MaxSizeContainer`（最大 320×360dp、外边距 32dp）改成
 * `widthIn` + `aspectRatio(1f)`，页面点击（触发指纹）挂在根节点上。
 *
 * @param onViewCreated 把创建好的 [LockPatternView] 交回 Scene，用于设置错误态
 */
@Composable
fun SecurityScreen(
    canAuthenticate: Boolean,
    onPatternDetected: (List<LockPatternView.Cell>) -> Unit,
    onViewCreated: (LockPatternView) -> Unit,
    onBiometricClick: () -> Unit,
) {
    // 九宫格自己是 View，读不到 Compose 主题：三态颜色按当前色板传进去，
    // 否则深色页面上只剩「54% 黑」的默认色，等于看不见
    val regularColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val successColor = MaterialTheme.colorScheme.primary.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(enabled = canAuthenticate, onClick = onBiometricClick),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                LockPatternView(context).apply {
                    contentDescription = context.getString(R.string.app_name)
                    setOnPatternListener(
                        object : LockPatternView.OnPatternListener {
                            override fun onPatternStart() {}

                            override fun onPatternCleared() {}

                            override fun onPatternCellAdded(pattern: List<LockPatternView.Cell>) {}

                            override fun onPatternDetected(pattern: List<LockPatternView.Cell>) {
                                onPatternDetected(pattern)
                            }
                        },
                    )
                    onViewCreated(this)
                }
            },
            update = { view -> view.setPatternColors(regularColor, successColor, errorColor) },
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .aspectRatio(1f),
        )
    }
}
