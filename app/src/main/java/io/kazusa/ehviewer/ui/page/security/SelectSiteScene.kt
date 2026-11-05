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
package io.kazusa.ehviewer.ui.page.security

import androidx.compose.runtime.Composable
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.ui.compose.nav.CHECK_STEP_SELECT_SITE
import io.kazusa.ehviewer.ui.compose.nav.EhRoute
import io.kazusa.ehviewer.ui.compose.nav.ehNavigator
import io.kazusa.ehviewer.ui.compose.nav.nextCheckRoute
import io.kazusa.ehviewer.ui.compose.nav.targetOf
import io.kazusa.ehviewer.ui.compose.screen.SelectSiteScreen

/**
 * 选站点页——守卫链的一跳。
 *
 * P7-c 出壳：页面不再是 `EhComposeScene` 的子类，而是直接挂在宿主 `NavDisplay`
 * 上的一段 @Composable；选完站点由 [nextCheckRoute] 算出下一站，
 * 再用 [io.kazusa.ehviewer.ui.compose.nav.EhNavigator.replaceTop] 把这一跳摘掉。
 */
@Composable
fun SelectSiteScene(route: EhRoute.SelectSite) {
    val navigator = ehNavigator()
    SelectSiteScreen(
        onConfirm = { site ->
            Settings.putSelectSite(false)
            Settings.putGallerySite(site)
            navigator.replaceTop(nextCheckRoute(CHECK_STEP_SELECT_SITE, targetOf(route)))
        },
    )
}
