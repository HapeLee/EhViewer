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
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.ui.scene.FavoritesScene
import com.hippo.ehviewer.ui.scene.GalleryCommentsScene
import com.hippo.ehviewer.ui.scene.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.GalleryInfoScene
import com.hippo.ehviewer.ui.scene.GalleryListScene
import com.hippo.ehviewer.ui.scene.GalleryPreviewsScene
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.ReaderScene
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.util.getParcelableCompat

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

/** 旧协议里「按字段拼查询」的动作名 */
private val LIST_URL_BUILDER_ACTION get() = GalleryListScene.ACTION_LIST_URL_BUILDER

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

/** `Announcer`（旧协议）→ route；只用于迁移期还没改成 route 的入口 */
fun routeOf(announcer: Announcer): EhRoute = legacyRouteOf(announcer.clazz.name, announcer.args) ?: homepageRoute()

/** 页面实例 → route；弹栈时用它定位自己 */
fun routeOf(scene: SceneFragment): EhRoute? = legacyRouteOf(scene.javaClass.name, scene.arguments)

/** 取出守卫 route 携带的目标页 */
fun targetOf(route: EhRoute?): EhRoute? = when (route) {
    is EhRoute.Security -> route.target
    is EhRoute.SignIn -> route.target
    is EhRoute.SelectSite -> route.target
    is EhRoute.GalleryList, is EhRoute.GalleryDetail, is EhRoute.GalleryInfo,
    is EhRoute.GalleryPreviews, is EhRoute.GalleryComments, is EhRoute.Progress, is EhRoute.Reader,
    is EhRoute.Favorites, is EhRoute.History, is EhRoute.Downloads, EhRoute.CookieSignIn,
    EhRoute.WebViewSignIn,
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
    EhRoute.WebViewSignIn,
    -> null
}

/** 下载页的旧 Scene 名。类已经删了，名字留给下载服务的通知 Intent 用 */
const val SCENE_NAME_DOWNLOADS = "com.hippo.ehviewer.ui.scene.DownloadsScene"

/** 下载页 Intent 里「定位到某一项」的 key */
const val KEY_SCENE_DOWNLOAD_GID = "gid"

/** 下载页 Intent 里「顺带做什么」的 key */
const val KEY_SCENE_DOWNLOAD_ACTION = "action"

/** 下载页 Intent：清掉已完成的下载服务 */
const val SCENE_DOWNLOAD_ACTION_CLEAR_SERVICE = "clear_download_service"

/**
 * route → 还需要 `CompatSceneEntry` 承载的旧 Scene 类名；已经出壳的页面返回 `null`。
 *
 * P7-c 一页一页删，等这里返回的全是 `null` 时，`CompatSceneEntry` 和
 * `com.hippo.scene` 就能一起删掉（P7-e）。
 */
fun compatSceneClassNameOf(route: EhRoute): String? = when (route) {
    // 已经出壳的页面不再需要 Fragment
    is EhRoute.Security, is EhRoute.SignIn, is EhRoute.SelectSite,
    EhRoute.CookieSignIn, EhRoute.WebViewSignIn, EhRoute.History,
    is EhRoute.Downloads,
    -> null
    EhRoute.Favorites -> FavoritesScene::class.java.name
    is EhRoute.GalleryList -> GalleryListScene::class.java.name
    is EhRoute.GalleryDetail -> GalleryDetailScene::class.java.name
    is EhRoute.GalleryInfo -> GalleryInfoScene::class.java.name
    is EhRoute.GalleryPreviews -> GalleryPreviewsScene::class.java.name
    is EhRoute.GalleryComments -> GalleryCommentsScene::class.java.name
    is EhRoute.Progress -> ProgressScene::class.java.name
    is EhRoute.Reader -> ReaderScene::class.java.name
}

fun compatSceneClassOf(route: EhRoute): Class<out SceneFragment>? = compatSceneClassNameOf(route)?.let { Class.forName(it).asSubclass(SceneFragment::class.java) }

/**
 * 兼容承载时 Fragment 的 tag（同时兼作容器 id 的种子）。
 *
 * 不能用类名：同一个 Scene 类可能同时在栈里有多份（比如不同查询条件的列表页），
 * 类名撞车会让 FragmentManager 把旧 fragment 往新容器里塞，直接抛
 * `Can't change container ID of fragment`。route 的 `toString()` 对数据类是稳定的，
 * 旋转重建后能算出同一个 tag、同一个容器 id。
 */
fun compatSceneKeyOf(route: EhRoute): String? = compatSceneClassNameOf(route)?.let { "$it#$route" }

/**
 * route → 旧 `arguments`。
 *
 * [detail] 是导航门面从缓存里补的上下文：详情/信息/预览/评论页拿到它就能免掉一次
 * 「先出骨架再刷新」的等待，拿不到就退化成按 id 请求。
 */
fun EhRoute.toLegacyArgs(detail: GalleryDetail? = null): Bundle = when (this) {
    // 已经出壳的页面不再需要旧 args
    EhRoute.CookieSignIn, EhRoute.WebViewSignIn, EhRoute.Favorites, EhRoute.History,
    is EhRoute.Security, is EhRoute.SignIn, is EhRoute.SelectSite,
    -> Bundle()
    // 下载页靠 Intent 起，不靠 arguments 起（见 [SCENE_NAME_DOWNLOADS]）
    is EhRoute.Downloads -> Bundle()
    is EhRoute.GalleryList -> Bundle().apply {
        val targetAction = query.action ?: LIST_URL_BUILDER_ACTION
        putString(GalleryListScene.KEY_ACTION, targetAction)
        if (targetAction == LIST_URL_BUILDER_ACTION) {
            putParcelable(GalleryListScene.KEY_LIST_URL_BUILDER, query.toListUrlBuilder())
        }
    }
    is EhRoute.GalleryDetail -> Bundle().apply {
        if (page == 0 && detail != null) {
            putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO)
            putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, detail)
        } else {
            putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GID_TOKEN)
            putLong(GalleryDetailScene.KEY_GID, gid)
            putString(GalleryDetailScene.KEY_TOKEN, token)
            putInt(GalleryDetailScene.KEY_PAGE, page)
        }
    }
    is EhRoute.GalleryInfo -> Bundle().apply {
        putParcelable(GalleryInfoScene.KEY_GALLERY_DETAIL, detail)
    }
    is EhRoute.GalleryPreviews -> Bundle().apply {
        putParcelable(GalleryPreviewsScene.KEY_GALLERY_INFO, detail)
        putInt(GalleryPreviewsScene.KEY_SCROLL_TO, scrollTo)
    }
    is EhRoute.GalleryComments -> Bundle().apply {
        putInt(GalleryCommentsScene.KEY_API_UID, apiUid.toInt())
        putString(GalleryCommentsScene.KEY_API_KEY, apiKey)
        putLong(GalleryCommentsScene.KEY_GID, gid)
        putString(GalleryCommentsScene.KEY_TOKEN, token)
        putParcelable(GalleryCommentsScene.KEY_GALLERY_DETAIL, detail)
    }
    is EhRoute.Progress -> Bundle().apply {
        putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN)
        putLong(ProgressScene.KEY_GID, gid)
        putString(ProgressScene.KEY_PTOKEN, pToken)
        putInt(ProgressScene.KEY_PAGE, page)
    }
    // 阅读器走 GalleryActivity 的 Uri 通道，旧协议无法表达
    is EhRoute.Reader -> Bundle()
}

/**
 * 旧 `arguments` → route；给 `EhComposeScene.startRoute` 用。
 *
 * 认不出来返回 null（未知类、参数缺失），调用方自行兜底。
 */
fun legacyRouteOf(sceneClassName: String, args: Bundle?): EhRoute? = when (sceneClassName) {
    FavoritesScene::class.java.name -> EhRoute.Favorites
    SCENE_NAME_DOWNLOADS -> EhRoute.Downloads(
        gid = args?.getLong(KEY_SCENE_DOWNLOAD_GID, -1L) ?: -1L,
        clearService = SCENE_DOWNLOAD_ACTION_CLEAR_SERVICE == args?.getString(KEY_SCENE_DOWNLOAD_ACTION),
    )
    ReaderScene::class.java.name -> EhRoute.Reader()
    ProgressScene::class.java.name -> EhRoute.Progress(
        gid = args?.getLong(ProgressScene.KEY_GID, -1L) ?: -1L,
        pToken = args?.getString(ProgressScene.KEY_PTOKEN),
        page = args?.getInt(ProgressScene.KEY_PAGE, 0) ?: 0,
    )
    GalleryListScene::class.java.name -> EhRoute.GalleryList(
        query = galleryQueryOf(
            lub = args?.getParcelableCompat<ListUrlBuilder>(GalleryListScene.KEY_LIST_URL_BUILDER),
            action = args?.getString(GalleryListScene.KEY_ACTION),
        ),
    )
    GalleryDetailScene::class.java.name -> {
        val info = args?.getParcelableCompat<GalleryInfo>(GalleryDetailScene.KEY_GALLERY_INFO)
        if (info != null) {
            EhRoute.GalleryDetail(gid = info.gid, token = info.token)
        } else {
            EhRoute.GalleryDetail(
                gid = args?.getLong(GalleryDetailScene.KEY_GID, -1L) ?: -1L,
                token = args?.getString(GalleryDetailScene.KEY_TOKEN),
                page = args?.getInt(GalleryDetailScene.KEY_PAGE, 0) ?: 0,
            )
        }
    }
    GalleryInfoScene::class.java.name -> EhRoute.GalleryInfo(
        gid = args?.getParcelableCompat<GalleryDetail>(GalleryInfoScene.KEY_GALLERY_DETAIL)?.gid ?: -1L,
    )
    GalleryPreviewsScene::class.java.name -> EhRoute.GalleryPreviews(
        gid = args?.getParcelableCompat<GalleryInfo>(GalleryPreviewsScene.KEY_GALLERY_INFO)?.gid ?: -1L,
        scrollTo = args?.getInt(GalleryPreviewsScene.KEY_SCROLL_TO, -1) ?: -1,
    )
    GalleryCommentsScene::class.java.name -> {
        val detail = args?.getParcelableCompat<GalleryDetail>(GalleryCommentsScene.KEY_GALLERY_DETAIL)
        EhRoute.GalleryComments(
            gid = detail?.gid ?: (args?.getLong(GalleryCommentsScene.KEY_GID, -1L) ?: -1L),
            apiUid = detail?.apiUid ?: (args?.getInt(GalleryCommentsScene.KEY_API_UID, -1) ?: -1).toLong(),
            apiKey = detail?.apiKey ?: args?.getString(GalleryCommentsScene.KEY_API_KEY),
            token = detail?.token ?: args?.getString(GalleryCommentsScene.KEY_TOKEN),
        )
    }
    else -> null
}
