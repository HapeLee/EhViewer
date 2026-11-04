/*
 * Copyright 2019 Hippo Seven
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
package com.hippo.ehviewer.widget

import android.content.Context
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.host.DialogRequest

/**
 * WebView 的 `alert` / `confirm` / `prompt` 走宿主对话框（[DialogRequest]），
 * 不再自己 inflate `dialog_js_prompt.xml`——那个布局随本类一起删除，
 * `res/layout` 因此彻底清空。
 *
 * `context` 不是 `MainActivity` 时（理论上不会，WebView 都挂在宿主里）直接 `cancel()`，
 * 避免 js 端永久挂起。
 */
open class DialogWebChromeClient(private val context: Context) : WebChromeClient() {
    private val host: MainActivity? get() = context as? MainActivity

    override fun onJsAlert(view: WebView, url: String?, message: String, result: JsResult): Boolean {
        val host = host
        if (host == null) {
            result.cancel()
            return true
        }
        host.showDialog(
            DialogRequest.Message(
                title = null,
                message = message,
                positiveText = host.getString(android.R.string.ok),
                onPositive = { result.confirm() },
                onCancel = { result.cancel() },
            ),
        )
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String, result: JsResult): Boolean {
        val host = host
        if (host == null) {
            result.cancel()
            return true
        }
        host.showDialog(
            DialogRequest.Message(
                title = null,
                message = message,
                positiveText = host.getString(android.R.string.ok),
                negativeText = host.getString(android.R.string.cancel),
                onPositive = { result.confirm() },
                onNegative = { result.cancel() },
                onCancel = { result.cancel() },
            ),
        )
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        val host = host
        if (host == null) {
            result.cancel()
            return true
        }
        host.showDialog(
            DialogRequest.TextInput(
                title = null,
                hint = null,
                initialText = defaultValue.orEmpty(),
                positiveText = host.getString(android.R.string.ok),
                message = message,
                onConfirm = { text, _ -> result.confirm(text) },
                onCancel = { result.cancel() },
            ),
        )
        return true
    }
}
