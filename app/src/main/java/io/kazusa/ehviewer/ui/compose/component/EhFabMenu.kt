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
package io.kazusa.ehviewer.ui.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** FAB 菜单里的一项：图标 + 文案 + 动作 */
class EhFabAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * 列表页统一的 FAB：M3E 的 FAB menu（`FloatingActionButtonMenu` + `ToggleFloatingActionButton`）。
 *
 * 参考项目里每个列表页都是一组 mini FAB，这里把它们折成一个可展开的菜单：
 * 收起时只有一颗主 FAB，点开才列出动作，页面右下角不会再堆四五个圆钮。
 *
 * [visible] 为 false 时整组淡出（列表向下滚动、搜索条展开时由宿主持有）；
 * 藏起来之前会先收起菜单，避免留下一个悬空的菜单面板。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EhFabMenu(
    actions: List<EhFabAction>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) expanded = false }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        FloatingActionButtonMenu(
            expanded = expanded,
            button = {
                ToggleFloatingActionButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                ) {
                    Icon(
                        // 展开时换成叉，这是 FAB menu 的惯用反馈
                        imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = null,
                    )
                }
            },
        ) {
            actions.forEach { action ->
                FloatingActionButtonMenuItem(
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    text = { Text(action.label) },
                    icon = { Icon(action.icon, contentDescription = null) },
                )
            }
        }
    }
}
