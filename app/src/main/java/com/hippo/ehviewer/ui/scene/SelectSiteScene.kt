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
package com.hippo.ehviewer.ui.scene

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.CHECK_STEP_SELECT_SITE
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.compose.nav.ehNavigator
import com.hippo.ehviewer.ui.compose.nav.nextCheckRoute
import com.hippo.ehviewer.ui.compose.nav.targetOf
import com.hippo.ehviewer.ui.compose.screen.SelectSiteScreen

/**
 * 首个迁移到 Compose + M3E + Navigation 3 的 Scene。
 *
 * 守卫链的一跳：选完站点后由 [nextCheckRoute] 算出下一站，不再 `Class.forName` 反解目标。
 */
class SelectSiteScene : EhComposeScene() {

    override fun needShowLeftDrawer(): Boolean = false

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.SelectSite> {
            SelectSiteScreen(
                onConfirm = { site ->
                    Settings.putSelectSite(false)
                    Settings.putGallerySite(site)
                    ehNavigator().replaceTop(nextCheckRoute(CHECK_STEP_SELECT_SITE, targetOf(ehRoute)))
                },
            )
        }
    }
}
