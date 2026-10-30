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
package com.hippo.ehviewer.ui.compose.nav

import android.app.Activity
import androidx.fragment.app.Fragment
import com.hippo.ehviewer.EhApplication.Companion.galleryDetailCache
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.scene.SceneHost

/**
 * 全应用唯一的导航入口。
 *
 * P7-a 阶段它的实现转调旧 Fragment 栈（[SceneHost]），行为零变化；
 * 价值在于调用方从此只说 route，P7-b 把 [SceneHost] 的实现换成单一 `NavDisplay`
 * 之后，调用点一行都不用动。
 *
 * 只保留旧栈真能表达的三个动作：[push]、[pop]、[replaceTop]。
 * `popTo(route)` 需要能遍历返回栈，属于 P7-b（单栈宿主）的能力，这里不造半成品。
 */
interface EhNavigator {
    /** 压入一个页面 */
    fun push(route: EhRoute)

    /** 弹出当前页面（等价于 `finish()`） */
    fun pop()

    /** 用新页面替换当前页面（压新的、摘旧的），用于「进度页 → 详情页」这类中转页 */
    fun replaceTop(route: EhRoute)
}

/** 从任意 Fragment 取导航门面；弹出的是这个 Fragment 自己 */
fun Fragment.ehNavigator(): EhNavigator = HostNavigator(
    hostProvider = { activity as? SceneHost },
    topSceneProvider = { this as? SceneFragment },
)

/** 从宿主 Activity 取导航门面；Intent / 剪贴板 / 导航菜单这类入口用 */
fun Activity.ehNavigator(): EhNavigator = HostNavigator(
    hostProvider = { this as? SceneHost },
    topSceneProvider = { (this as? SceneHost)?.topScene },
)

private class HostNavigator(
    private val hostProvider: () -> SceneHost?,
    private val topSceneProvider: () -> SceneFragment?,
) : EhNavigator {
    override fun push(route: EhRoute) {
        val host = hostProvider() ?: return
        // route 只带 id，需要整块 GalleryDetail 的页面在这里补一次缓存上下文
        val announcer = Announcer(sceneClassOf(route))
            .setArgs(route.toLegacyArgs(cachedGalleryDetail(route)))
        host.startScene(announcer)
    }

    override fun pop() {
        topSceneProvider()?.finish()
    }

    /**
     * 先压新的、再摘旧的。
     *
     * 顺序不能反：旧栈在只剩一个页面时会直接 `finish()` Activity，
     * 先弹后压会把宿主一起关掉。动作和「进度页换详情页」的历史实现一致。
     */
    override fun replaceTop(route: EhRoute) {
        val host = hostProvider() ?: return
        val old = topSceneProvider()
        push(route)
        if (old != null) {
            host.finishScene(old)
        }
    }
}
