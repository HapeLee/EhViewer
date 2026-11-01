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

import android.os.Bundle
import com.hippo.ehviewer.EhApplication.Companion.galleryDetailCache
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.ListUrlBuilder
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer

/**
 * route 与遗留 `Bundle` 协议的互转层。
 *
 * 迁移期（P7-a）调用方已经只说 route，但页面仍然只认 `arguments`，所以这层负责
 * 「route → 旧 args」；等页面出壳（P7-c）之后删掉即可。
 *
 * 三条纪律：
 * 1. 纯函数化的部分（[galleryQueryOf] / [GalleryQuery.toListUrlBuilder]）不碰 Android，
 *    可以直接单测；
 * 2. 需要整块 `GalleryDetail` 的页面（信息 / 预览 / 评论）只从缓存补全，route 里不放 Parcelable；
 * 3. 旧协议表达不了的路由（阅读器的 `Uri`）**不做倒退改造**，等页面出壳时顺手处理。
 *
 * 守卫链（手势锁 → 登录 → 选站点 → 目标页）原来靠 `target_scene` + `Class.forName` 串起来，
 * 现在由 [checkStepRoute] 这个纯函数表达：跑完一跳就用 [nextCheckRoute] 问下一跳该去哪。
 */
private const val UPLOADER_KEYWORD_PREFIX = "uploader:"

/**
 * 画廊列表的入口动作。
 *
 * 值与旧 `GalleryListScene.ACTION_*` 逐字一致：[Settings.launchPageGalleryListSceneAction]
 * 存在偏好里，换名字会让老用户的「启动页」设置失效。
 */
const val LIST_ACTION_HOMEPAGE = "action_homepage"
const val LIST_ACTION_SUBSCRIPTION = "action_subscription"
const val LIST_ACTION_WHATS_HOT = "action_whats_hot"
const val LIST_ACTION_TOP_LIST = "action_top_list"
const val LIST_ACTION_LIST_URL_BUILDER = "action_list_url_builder"

/** 旧协议里「按字段拼查询」的动作名 */
private const val LIST_URL_BUILDER_ACTION = LIST_ACTION_LIST_URL_BUILDER

/** 守卫链的步骤，顺序即检查顺序 */
const val CHECK_STEP_SECURITY = 0
const val CHECK_STEP_SIGN_IN = 1
const val CHECK_STEP_SELECT_SITE = 2

/** 守卫链里「目标页」的落点二选一：有 target 就去 target，没有就回到首页 */
fun homepageRoute(action: String = Settings.launchPageGalleryListSceneAction): EhRoute = EhRoute.GalleryList(GalleryQuery(action = action))

/**
 * 守卫链的核心：给定当前步骤和「链走完要去哪」，算出这一跳该停在哪个页面。
 *
 * 写成纯函数是为了能直接断言组合——登录态 / 站点选择 / 目标有无一共 8 种情况，
 * 之前散在 `SolidScene` 的三个分支里，谁也没覆盖全。
 */
fun checkStepRoute(
    step: Int,
    target: EhRoute?,
    needSignedIn: Boolean,
    needSelectSite: Boolean,
    homepage: EhRoute = homepageRoute(),
): EhRoute = when (step) {
    CHECK_STEP_SECURITY -> if (needSignedIn) {
        EhRoute.SignIn(target)
    } else {
        checkStepRoute(CHECK_STEP_SIGN_IN, target, needSignedIn, needSelectSite, homepage)
    }
    CHECK_STEP_SIGN_IN -> if (needSelectSite) {
        EhRoute.SelectSite(target)
    } else {
        checkStepRoute(CHECK_STEP_SELECT_SITE, target, needSignedIn, needSelectSite, homepage)
    }
    else -> target ?: homepage
}

/** 冷启动 / Intent 冷启动时的第一跳：有手势锁先过锁，否则从 [CHECK_STEP_SECURITY] 开始 */
fun initialCheckRoute(target: EhRoute? = null): EhRoute = if (!Settings.security.isNullOrEmpty()) {
    EhRoute.Security(target)
} else {
    checkStepRoute(
        step = CHECK_STEP_SECURITY,
        target = target,
        needSignedIn = EhUtils.needSignedIn(),
        needSelectSite = Settings.selectSite,
    )
}

/** 某一跳守卫完成后，问下一跳该去哪（由当前全局状态决定） */
fun nextCheckRoute(step: Int, target: EhRoute?): EhRoute = checkStepRoute(
    step = step,
    target = target,
    needSignedIn = EhUtils.needSignedIn(),
    needSelectSite = Settings.selectSite,
)

/** 取出守卫 route 携带的目标页 */
fun targetOf(route: EhRoute?): EhRoute? = when (route) {
    is EhRoute.Security -> route.target
    is EhRoute.SignIn -> route.target
    is EhRoute.SelectSite -> route.target
    is EhRoute.GalleryList, is EhRoute.GalleryDetail, is EhRoute.GalleryInfo,
    is EhRoute.GalleryPreviews, is EhRoute.GalleryComments, is EhRoute.Progress,
    is EhRoute.Favorites, is EhRoute.History, is EhRoute.Downloads, is EhRoute.Reader,
    EhRoute.CookieSignIn, EhRoute.WebViewSignIn, EhRoute.Settings, EhRoute.About,
    is EhRoute.SettingsCategory,
    -> null
    null -> null
}

/** 需要整块 GalleryDetail 的页面在这里补缓存上下文 */
fun cachedGalleryDetail(route: EhRoute): GalleryDetail? = gidOf(route)?.let { runCatching { galleryDetailCache.get(it) }.getOrNull() }

/**
 * `ListUrlBuilder` → [GalleryQuery]。
 *
 * `ListUrlBuilder.keyword` 的 getter 在 `MODE_UPLOADER` 下会带上 `uploader:` 前缀，
 * 这里剥掉一层，保证 `query → lub → query` 往返一致。
 */
fun galleryQueryOf(lub: ListUrlBuilder?, action: String? = LIST_URL_BUILDER_ACTION): GalleryQuery {
    if (lub == null) {
        return GalleryQuery(action = action)
    }
    return GalleryQuery(
        action = action,
        mode = lub.mode,
        category = lub.category,
        keyword = lub.keyword?.removePrefix(UPLOADER_KEYWORD_PREFIX),
        advanceSearch = lub.advanceSearch,
        minRating = lub.minRating,
        pageFrom = lub.pageFrom,
        pageTo = lub.pageTo,
        showExpunged = lub.isShowExpunged,
        hash = lub.hash,
    )
}

/** [GalleryQuery] → `ListUrlBuilder`；`keyword` 走 setter，存的是不带前缀的原值 */
fun GalleryQuery.toListUrlBuilder(): ListUrlBuilder {
    val rawKeyword = keyword
    return ListUrlBuilder(
        mode = mode,
        category = category,
        hash = hash,
        advanceSearch = advanceSearch,
        minRating = minRating,
        pageFrom = pageFrom,
        pageTo = pageTo,
        isShowExpunged = showExpunged,
    ).apply {
        keyword = rawKeyword
    }
}

/** route 指向的画廊 id；不需要画廊上下文的 route 返回 null */
fun gidOf(route: EhRoute): Long? = when (route) {
    is EhRoute.GalleryDetail -> route.gid.takeIf { it != -1L }
    is EhRoute.GalleryInfo -> route.gid.takeIf { it != -1L }
    is EhRoute.GalleryPreviews -> route.gid.takeIf { it != -1L }
    is EhRoute.GalleryComments -> route.gid.takeIf { it != -1L }
    is EhRoute.Progress -> route.gid.takeIf { it != -1L }
    is EhRoute.Reader -> route.gid.takeIf { it != -1L }
    is EhRoute.GalleryList, is EhRoute.Security, is EhRoute.SignIn, is EhRoute.SelectSite,
    is EhRoute.Favorites, is EhRoute.History, is EhRoute.Downloads, EhRoute.CookieSignIn,
    EhRoute.WebViewSignIn, EhRoute.Settings, EhRoute.About, is EhRoute.SettingsCategory,
    -> null
}

/**
 * 下载页的旧 Scene 名。
 *
 * 页面类已经删了（`ui.page.downloads.DownloadsScene` 是 @Composable），
 * 但 `DownloadService` 在服务进程里只能发这个字符串，改值会让运行中的服务打不开下载页。
 * 只当协议常量看，不要拿它反射。
 */
const val SCENE_NAME_DOWNLOADS = "com.hippo.ehviewer.ui.scene.DownloadsScene"

/** 下载页 Intent 里「定位到某一项」的 key */
const val KEY_SCENE_DOWNLOAD_GID = "gid"

/** 下载页 Intent 里「顺带做什么」的 key */
const val KEY_SCENE_DOWNLOAD_ACTION = "action"

/** 下载页 Intent：清掉已完成的下载服务 */
const val SCENE_DOWNLOAD_ACTION_CLEAR_SERVICE = "clear_download_service"

/**
 * Intent 里的「场景名 + 基础字段」→ route。
 *
 * P7-e 之后只剩下载页还走这条路：`DownloadService` 跑在服务里，只能给名字和几个标量。
 * 其余页面都在宿主内部用 route 压栈，不再有「类名 + arguments」这一层。
 */
fun legacyRouteOf(sceneName: String, args: Bundle?): EhRoute? = when (sceneName) {
    SCENE_NAME_DOWNLOADS -> EhRoute.Downloads(
        gid = args?.getLong(KEY_SCENE_DOWNLOAD_GID, -1L) ?: -1L,
        clearService = SCENE_DOWNLOAD_ACTION_CLEAR_SERVICE == args?.getString(KEY_SCENE_DOWNLOAD_ACTION),
    )
    else -> null
}

private val routeCbor = Cbor { ignoreUnknownKeys = true }

/**
 * 跨任务打开宿主时携带的 route 载荷（CBOR）。
 *
 * 取代旧的「场景类名 + `Bundle` 协议」：`UrlOpener` 在别的窗口里拿到画廊链接时，
 * 没法直接调用宿主的返回栈，只能把 route 塞进 `Intent`，宿主解码后再压栈。
 * 序列化格式与 Nav3 保存返回栈时用的是同一套 `@Serializable` 定义。
 */
fun encodeRoute(route: EhRoute): ByteArray = routeCbor.encodeToByteArray(serializer<EhRoute>(), route)

/** 解码失败（旧版本残留的 Intent、格式变更）返回 `null`，调用方兜底到初始页 */
fun decodeRoute(bytes: ByteArray): EhRoute? = runCatching {
    routeCbor.decodeFromByteArray(serializer<EhRoute>(), bytes)
}.getOrNull()
