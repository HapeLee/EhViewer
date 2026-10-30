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
package com.hippo.ehviewer.ui.host

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.SoftwareKeyboardControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 页面在组合里能拿到的宿主壳能力（抽屉 / Snackbar / 头像）。
 *
 * 与 [EhHost] 是同一组语义，区别只是这一份是给纯 @Composable 页面用的：
 * 页面不再需要继承 `BaseScene`、也不再 `activity as? EhHost`。
 * P7-e 删掉 `BaseScene` / `EhHost` 之后，这里就是唯一一份定义。
 *
 * 刻意只放「壳」能力：页面自己的对话框、剪贴板、下载入口仍然走 Activity 上下文。
 */
@Immutable
interface EhHostState {
    fun showTip(message: CharSequence, length: Int)
    fun showTip(@StringRes id: Int, length: Int)

    /** @param resId 0 for clear */
    fun setNavCheckedItem(@IdRes resId: Int)
    fun setDrawerLockMode(lockMode: Int, edgeGravity: Int)
    fun toggleDrawer(drawerGravity: Int)
    fun updateProfile()
    fun hideSoftInput()
}

/**
 * 宿主壳能力在组合树里的入口。
 *
 * 默认是 `null` 而不是抛异常：页面据此知道「自己不在宿主里」（预览 / 单测），
 * 提示类调用应当安静失效，而不是崩掉。
 */
val LocalEhHost = staticCompositionLocalOf<EhHostState?> { null }

/** 取宿主壳能力；不在宿主里时返回 `null`，调用方自行降级 */
@Composable
fun ehHost(): EhHostState? = LocalEhHost.current

/** 取宿主壳能力，缺失时降级为静默的空实现 */
@Composable
fun ehHostOrNoOp(): EhHostState = LocalEhHost.current ?: NoOpEhHost

/**
 * 宿主回到前台时执行 [action]（对应旧 Scene 的 `onResume()`）。
 *
 * 出壳后的页面没有 Fragment 生命周期，但「回到前台」这件事仍然要能感知：
 * 手势锁靠它自动拉起指纹，登录页靠它收子页的登录结果。
 */
@Composable
fun OnHostResume(action: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentAction by rememberUpdatedState(action)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentAction()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 组合有可能是 Activity 已经 RESUMED 之后才建起来的（比如旋转重建），补一次
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            currentAction()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private object NoOpEhHost : EhHostState {
    override fun showTip(message: CharSequence, length: Int) = Unit
    override fun showTip(id: Int, length: Int) = Unit
    override fun setNavCheckedItem(resId: Int) = Unit
    override fun setDrawerLockMode(lockMode: Int, edgeGravity: Int) = Unit
    override fun toggleDrawer(drawerGravity: Int) = Unit
    override fun updateProfile() = Unit
    override fun hideSoftInput() = Unit
}
