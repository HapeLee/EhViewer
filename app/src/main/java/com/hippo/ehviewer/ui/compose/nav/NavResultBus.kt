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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** 页面之间一次性事件的类型；**不承载状态**，状态一律去仓库读 */
sealed interface NavSignal {
    /** 登录链的某个子页完成了登录（cookie 已落 `EhCookieStore`，资料已落 `Settings`） */
    data class SignedIn(val source: SignInSource) : NavSignal
}

/** 登录来源，决定父页面收尾时要补哪一步 */
enum class SignInSource {
    /** 账号密码直登，父页面不需要额外动作 */
    PASSWORD,

    /** Cookie 登录，父页面要跑 `finishSignIn()` 的健康检查 */
    COOKIE,

    /** WebView 登录，父页面要补一次 `getProfile()` */
    WEB_VIEW,
}

/**
 * 一次性导航信号的投递箱（消费即清）。
 *
 * 取代旧的 `SceneFragment.setResult` / `onSceneResult` 回传：
 * - **状态**（评论、收藏槽位、下载状态、登录态）走仓库 / DAO，页面重新进入自己读；
 * - **一次性事件**（"登录子页完成了"）才走这里。
 *
 * 为什么不用 `SharedFlow`：事件发出时订阅方（父页面）通常处于 STOPPED，
 * 没有 replay 就会丢；`StateFlow` + [consume] 让"迟到的订阅方"也能拿到最后一条，
 * 而 [consume] 读完立刻清空，保证不会重复触发。
 */
object NavResultBus {
    private val pending = MutableStateFlow<NavSignal?>(null)

    fun publish(signal: NavSignal) {
        pending.value = signal
    }

    /** 取走并清空（原子）；没有事件时返回 null */
    fun consume(): NavSignal? = pending.getAndUpdate { null }

    /** 只读查看，不清空；给需要"先判断再消费"的场景 */
    fun peek(): NavSignal? = pending.value
}
