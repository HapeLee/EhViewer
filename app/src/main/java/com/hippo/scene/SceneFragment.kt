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

import android.app.assist.AssistContent
import android.os.Bundle
import android.view.View
import androidx.annotation.IntDef
import androidx.fragment.app.Fragment
import com.hippo.ehviewer.R
import rikka.core.res.resolveDrawable

/**
 * 页面基类。
 *
 * 栈操作全部交给 [SceneHost]：这里不再出现 `activity is StageActivity` 这类判断，
 * 所以把宿主换成单一 `NavDisplay` 时，页面代码不需要跟着改。
 * 旧的 `setResult` / `onSceneResult` 回传机制已移除——状态走仓库、一次性事件走
 * `NavResultBus`。
 */
open class SceneFragment : Fragment() {
    open fun onNewArguments(args: Bundle) {}

    /** 承载本页面的宿主；attach 之前为 null */
    val sceneHost: SceneHost?
        get() = activity as? SceneHost

    fun startScene(announcer: Announcer, horizontal: Boolean) {
        sceneHost?.startScene(announcer, horizontal)
    }

    fun startScene(announcer: Announcer) {
        sceneHost?.startScene(announcer)
    }

    fun finish(transitionHelper: TransitionHelper? = null) {
        val host = sceneHost ?: return
        // 转场只有旧 Fragment 栈能表达；单一 NavDisplay 宿主忽略它
        host.finishScene(this)
    }

    fun finishStage() {
        activity?.finish()
    }

    /**
     * @return negative for error
     */
    val stackIndex: Int
        get() = sceneHost?.indexOfScene(this) ?: -1

    open fun onBackPressed() {
        finish()
    }

    open fun onProvideAssistContent(outContent: AssistContent) {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setTag(R.id.fragment_tag, tag)
        view.background =
            requireActivity().getTheme().resolveDrawable(android.R.attr.windowBackground)
        sceneHost?.onSceneViewCreated(this, savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sceneHost?.onSceneViewDestroyed(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneHost?.onSceneDestroyed(this)
    }

    @IntDef(LAUNCH_MODE_STANDARD, LAUNCH_MODE_SINGLE_TOP, LAUNCH_MODE_SINGLE_TASK)
    @Retention(
        AnnotationRetention.SOURCE,
    )
    annotation class LaunchMode

    companion object {
        const val LAUNCH_MODE_STANDARD = 0
        const val LAUNCH_MODE_SINGLE_TOP = 1
        const val LAUNCH_MODE_SINGLE_TASK = 2
    }
}
