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

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith

/**
 * 页面进退场动画（取自 `Matter` 的 `NavTransitions`，与 legado-with-MD3 的曲线/时长一致）。
 *
 * 三条规则：
 * 1. 前进：新页从右侧整屏滑入（480ms `FastOutSlowInEasing`）+ 淡入（360ms `LinearOutSlowInEasing`）；
 *    旧页只左移 1/4 屏（视差）并淡出。
 * 2. 返回：旧页从 -1/4 屏视差位置滑回；新页原地缩到 0.8x 并淡出。
 * 3. 预测性返回：与返回同一套动画，但所有组件共用一个不带时长的 `tween`——
 *    手势进度直接驱动插值，手指停下动画就停下。
 *
 * 时长常量放在这里而不是散在调用处：三处 spec（`transitionSpec` / `popTransitionSpec` /
 * `predictivePopTransitionSpec`）必须共用一套参数，否则手势中途松手会跳变。
 */
object NavTransitions {
    const val SLIDE_DURATION_MILLIS = 480
    const val FADE_DURATION_MILLIS = 360

    /** 进入新页面：从右往左滑入 + 渐显 */
    fun <S> enterTransition(scope: AnimatedContentTransitionScope<S>): EnterTransition = scope.slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(
            durationMillis = SLIDE_DURATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        initialOffset = { fullWidth -> fullWidth },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = FADE_DURATION_MILLIS,
            easing = LinearOutSlowInEasing,
        ),
    )

    /** 当前页退出：左移 1/4 屏做视差 + 渐隐 */
    fun <S> exitTransition(scope: AnimatedContentTransitionScope<S>): ExitTransition = scope.slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(
            durationMillis = SLIDE_DURATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        targetOffset = { fullWidth -> fullWidth / 4 },
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = FADE_DURATION_MILLIS,
            easing = LinearOutSlowInEasing,
        ),
    )

    /** 返回时上一页恢复：从 -1/4 屏视差滑回 + 渐显 */
    fun <S> popEnterTransition(scope: AnimatedContentTransitionScope<S>): EnterTransition = scope.slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(
            durationMillis = SLIDE_DURATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        initialOffset = { fullWidth -> -fullWidth / 4 },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = FADE_DURATION_MILLIS,
            easing = LinearOutSlowInEasing,
        ),
    )

    /** 返回时当前页留在原地缩到 0.8x 再淡出 */
    fun popExitTransition(): ExitTransition = scaleOut(
        targetScale = 0.8f,
        animationSpec = tween(SLIDE_DURATION_MILLIS, easing = FastOutSlowInEasing),
    ) + fadeOut(animationSpec = tween(FADE_DURATION_MILLIS))

    fun <S> forward(scope: AnimatedContentTransitionScope<S>): ContentTransform = enterTransition(scope) togetherWith exitTransition(scope)

    fun <S> pop(scope: AnimatedContentTransitionScope<S>): ContentTransform = popEnterTransition(scope) togetherWith popExitTransition()

    /** 预测性返回：不带时长，由手势进度插值 */
    fun <S> predictivePop(scope: AnimatedContentTransitionScope<S>): ContentTransform {
        val enter = scope.slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(easing = FastOutSlowInEasing),
            initialOffset = { -it / 4 },
        ) + fadeIn(tween(easing = LinearOutSlowInEasing))
        val exit = scaleOut(targetScale = 0.8f, animationSpec = tween(easing = FastOutSlowInEasing)) +
            fadeOut(tween())
        return enter togetherWith exit
    }
}
