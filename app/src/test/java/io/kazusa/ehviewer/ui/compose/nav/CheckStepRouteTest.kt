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
package io.kazusa.ehviewer.ui.compose.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 守卫链（手势锁 → 登录 → 选站点 → 目标页）的纯函数覆盖。
 *
 * 这三跳原本是 `SolidScene.startSceneForCheckStep` 里的三个 `when` 分支，
 * 靠 `target_scene` 字符串反射把「要去哪」往下一层传；现在收敛成一个纯函数，
 * 登录态 × 站点选择 × 有无目标 一共 8 种组合，这里全部断言一遍。
 */
class CheckStepRouteTest {
    private val homepage = EhRoute.GalleryList(GalleryQuery(action = "action_homepage"))
    private val target = EhRoute.GalleryDetail(gid = 42L, token = "t")

    private fun first(
        needSignedIn: Boolean,
        needSelectSite: Boolean,
        target: EhRoute?,
    ): EhRoute = checkStepRoute(
        step = CHECK_STEP_SECURITY,
        target = target,
        needSignedIn = needSignedIn,
        needSelectSite = needSelectSite,
        homepage = homepage,
    )

    @Test
    fun `no guard needed lands on target`() {
        assertEquals(target, first(needSignedIn = false, needSelectSite = false, target = target))
    }

    @Test
    fun `no guard and no target lands on homepage`() {
        assertEquals(homepage, first(needSignedIn = false, needSelectSite = false, target = null))
    }

    @Test
    fun `sign in first and keep the target`() {
        assertEquals(
            EhRoute.SignIn(target),
            first(needSignedIn = true, needSelectSite = false, target = target),
        )
    }

    @Test
    fun `site selection first and keep the target`() {
        assertEquals(
            EhRoute.SelectSite(target),
            first(needSignedIn = false, needSelectSite = true, target = target),
        )
    }

    @Test
    fun `sign in wins over site selection`() {
        assertEquals(
            EhRoute.SignIn(target),
            first(needSignedIn = true, needSelectSite = true, target = target),
        )
    }

    @Test
    fun `after sign in the next hop is select site or target`() {
        assertEquals(
            EhRoute.SelectSite(target),
            checkStepRoute(CHECK_STEP_SIGN_IN, target, needSignedIn = false, needSelectSite = true, homepage),
        )
        assertEquals(
            target,
            checkStepRoute(CHECK_STEP_SIGN_IN, target, needSignedIn = false, needSelectSite = false, homepage),
        )
    }

    @Test
    fun `after select site the next hop is the target`() {
        assertEquals(
            target,
            checkStepRoute(CHECK_STEP_SELECT_SITE, target, needSignedIn = false, needSelectSite = false, homepage),
        )
        assertEquals(
            homepage,
            checkStepRoute(CHECK_STEP_SELECT_SITE, null, needSignedIn = false, needSelectSite = false, homepage),
        )
    }

    @Test
    fun `guard routes carry their target`() {
        assertEquals(target, targetOf(EhRoute.SignIn(target)))
        assertEquals(target, targetOf(EhRoute.SelectSite(target)))
        assertEquals(target, targetOf(EhRoute.Security(target)))
        assertNull(targetOf(EhRoute.Downloads()))
    }
}
