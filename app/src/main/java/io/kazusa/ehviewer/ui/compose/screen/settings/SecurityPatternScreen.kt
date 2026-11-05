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
package io.kazusa.ehviewer.ui.compose.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.support.widget.lockpattern.LockPatternUtils
import io.kazusa.ehviewer.support.widget.lockpattern.LockPatternView

/**
 * 手势锁设置（原 `SetSecurityFragment` + `activity_set_security.xml`）。
 *
 * `LockPatternView` 没法用 Compose 重画（自定义 View，内部把九宫格画在 canvas 上），所以
 * 用 `AndroidView` 挂进来；「取消 / 设置」两个按钮和指纹勾选改成 M3。
 *
 * 保存口径没变：格子数 ≤ 1 视为清除；指纹只在**真的存了图案**时才可能被打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityPatternScreen(
    initialPattern: String?,
    biometricAvailable: Boolean,
    initialFingerprintEnabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: (pattern: String, cellSize: Int, enableFingerprint: Boolean) -> Unit,
) {
    var fingerprintEnabled by rememberSaveable { mutableStateOf(initialFingerprintEnabled) }
    var patternView by remember { mutableStateOf<LockPatternView?>(null) }
    // 九宫格是 View，读不到 Compose 主题：三态颜色按当前色板传进去
    val regularColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val successColor = MaterialTheme.colorScheme.primary.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.set_pattern_protection)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.set_pattern_protection_tip),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { context ->
                        LockPatternView(context).apply {
                            if (!initialPattern.isNullOrEmpty()) {
                                setPattern(
                                    LockPatternView.DisplayMode.Correct,
                                    LockPatternUtils.stringToPattern(initialPattern),
                                )
                            }
                        }.also { patternView = it }
                    },
                    update = { view -> view.setPatternColors(regularColor, successColor, errorColor) },
                    modifier = Modifier
                        .sizeIn(maxWidth = 320.dp, maxHeight = 320.dp)
                        .fillMaxSize(),
                )
            }
            if (biometricAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fingerprintEnabled = !fingerprintEnabled }
                        .padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Checkbox(checked = fingerprintEnabled, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.enable_biometric),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = {
                        val view = patternView
                        onConfirm(
                            view?.patternString.orEmpty(),
                            view?.cellSize ?: 0,
                            fingerprintEnabled,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.set))
                }
            }
        }
    }
}
