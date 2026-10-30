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
package com.hippo.scene

import android.os.Bundle

/**
 * 承载页面栈的宿主。
 *
 * 抽出这个接口的目的只有一个：让 [SceneFragment] 依赖「谁管栈」这件事，
 * 而不是依赖 `StageActivity` 这个具体实现。P7-b 把宿主换成单一 `NavDisplay`
 * 之后，[SceneFragment] / `EhNavigator` / `BaseScene` 一行都不用改。
 *
 * 现有实现是 `EhNavHostActivity`（单一 Nav3 `NavDisplay` 返回栈）。
 * 新宿主只需要实现同一组语义：压栈、弹栈、取栈顶、给页面发 View 生命周期通知。
 */
interface SceneHost {
    /** 当前栈深度 */
    val sceneCount: Int

    /** 栈顶页面的实际类，用于「当前是不是安全页」这类判断 */
    val topSceneClass: Class<*>?

    /** 栈顶页面 */
    val topScene: SceneFragment?

    /** 压栈；[horizontal] 只影响旧栈的转场方向 */
    fun startScene(announcer: Announcer, horizontal: Boolean = false)

    /** 压栈并把已有同 launch-mode 的页面清掉（导航菜单用） */
    fun startSceneFirstly(announcer: Announcer)

    /** 弹栈指定页面 */
    fun finishScene(scene: SceneFragment)

    /** 页面在栈中的位置，找不到返回 -1 */
    fun indexOfScene(scene: SceneFragment): Int

    /** 页面 View 创建完成（宿主据此挂抽屉等壳组件） */
    fun onSceneViewCreated(scene: SceneFragment, savedInstanceState: Bundle?)

    /** 页面 View 即将销毁 */
    fun onSceneViewDestroyed(scene: SceneFragment)

    /** 页面本身销毁 */
    fun onSceneDestroyed(scene: SceneFragment)
}
