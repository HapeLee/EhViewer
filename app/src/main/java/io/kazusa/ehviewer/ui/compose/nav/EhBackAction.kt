/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kazusa.ehviewer.ui.compose.nav

/** 返回键在当前栈上的动作 */
enum class EhBackAction {
    /** 栈里还有上一页：弹出栈顶 entry */
    POP_ENTRY,

    /** 已经在页面根：把控制权交回宿主（关闭页面 / 双击退出） */
    POP_SCENE,
}

/**
 * 返回键判定。
 *
 * 双栈时代这段逻辑散在 15 个页面的 `onBackPressed` 里，收成纯函数之后
 * 宿主和页面用的是同一条规则：**先弹页面内的 entry，弹空了才关页面**。
 */
fun ehBackAction(backStackSize: Int): EhBackAction = if (backStackSize > 1) EhBackAction.POP_ENTRY else EhBackAction.POP_SCENE
