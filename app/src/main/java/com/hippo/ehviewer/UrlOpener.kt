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
package com.hippo.ehviewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.hippo.ehviewer.client.EhUrlOpener.parseUrl
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.encodeRoute
import com.hippo.ehviewer.ui.compose.theme.ehToolbarColor
import com.hippo.ehviewer.ui.host.EhNavHostActivity

object UrlOpener {
    /** 阅读器也在 NavHost 里了，URL 命中「本画廊第几页」时压它的 route，不再起 Activity */
    private fun openReader(context: Context, gid: Long, token: String?, page: Int) {
        startHostWithRoute(context, EhRoute.Reader(gid = gid, token = token, page = page))
    }

    private fun startHostWithRoute(context: Context, route: EhRoute) {
        val intent = Intent(context, MainActivity::class.java)
        intent.action = EhNavHostActivity.ACTION_START_SCENE
        intent.putExtra(EhNavHostActivity.KEY_SCENE_ROUTE, encodeRoute(route))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun openUrl(
        context: Context,
        url: String?,
        ehUrl: Boolean,
        galleryDetail: GalleryDetail? = null,
    ) {
        if (url.isNullOrEmpty()) {
            return
        }
        val uri = url.toUri()
        if (ehUrl) {
            galleryDetail?.let {
                val result = GalleryPageUrlParser.parse(url)
                if (result != null) {
                    if (result.gid == it.gid) {
                        openReader(context, it.gid, it.token, result.page)
                        return
                    }
                } else if (url.startsWith("#c")) {
                    try {
                        openReader(context, it.gid, it.token, url.replace("#c", "").toInt() - 1)
                        return
                    } catch (_: NumberFormatException) {
                    }
                }
            }
            parseUrl(url)?.let { route ->
                // 这里拿不到宿主的返回栈（context 可能是别的 Activity），
                // 所以把 route 本身塞进 Intent，宿主解码后压栈
                startHostWithRoute(context, route)
                return
            }
        }
        val isNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES > 0
        val customTabsIntent = CustomTabsIntent.Builder()
        customTabsIntent.setShowTitle(true)
        val params = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ehToolbarColor(context))
            .build()
        customTabsIntent.setDefaultColorSchemeParams(params)
        customTabsIntent.setColorScheme(if (isNight) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
        try {
            customTabsIntent.build().launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_browser_installed, Toast.LENGTH_LONG).show()
        }
    }
}
