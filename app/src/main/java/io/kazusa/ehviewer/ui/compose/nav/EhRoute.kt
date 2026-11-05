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

import androidx.annotation.IdRes
import androidx.navigation3.runtime.NavKey
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.EhUtils
import io.kazusa.ehviewer.client.data.ListUrlBuilder
import kotlinx.serialization.Serializable

/**
 * Navigation 3 的路由表。
 *
 * P7-a 起这里同时是「唯一导航入口的入参类型」：调用方不再拼 `Bundle` + `Announcer`，
 * 而是构造一个 route 交给 [EhNavigator]。参数用**字段**表达（而不是 Parcelable），
 * 所以路由可序列化、可深链、可在没有 Activity 的环境里断言。
 *
 * 迁移期 route 与旧 `Bundle` 的互转集中在 [io.kazusa.ehviewer.ui.compose.nav.EhRoutes]，
 * 页面本身仍然从 `arguments` / `rememberSaveable` 里取数据；等页面出壳（P7-c）后
 * 直接读 route 字段，这一层就能删掉。
 *
 * 约定：**只带标识，不带整块数据**。画廊详情/阅读器只传 `gid` + `token`，需要
 * `GalleryDetail` 的页面由导航门面从 `galleryDetailCache` 补全。
 */
@Serializable
sealed interface EhRoute : NavKey {
    /**
     * 首次启动 / 未选过站点时的站点选择页。
     *
     * [target] 是「选完站点之后真正要去的地方」，守卫链的最后一跳会落到它。
     */
    @Serializable
    data class SelectSite(val target: EhRoute? = null) : EhRoute

    /** 登录（账号密码表单）；[target] 同 [SelectSite.target] */
    @Serializable
    data class SignIn(val target: EhRoute? = null) : EhRoute

    /** Cookie 登录（ipb_member_id / ipb_pass_hash） */
    @Serializable
    data object CookieSignIn : EhRoute

    /** Web 登录宿主（WebView + 进度条） */
    @Serializable
    data object WebViewSignIn : EhRoute

    /** 手势锁；守卫链的第一站，[target] 同理是收尾后要去的地方 */
    @Serializable
    data class Security(val target: EhRoute? = null) : EhRoute

    /** 收藏（分类抽屉 + 多选修改） */
    @Serializable
    data object Favorites : EhRoute

    /** 历史记录（分页列表 + 左滑删除） */
    @Serializable
    data object History : EhRoute

    /**
     * 下载列表（标签抽屉 + 多选操作）。
     *
     * 通知栏入口用：[gid] 定位到某个下载项，[clearService] 是下载完成通知的「清掉服务」动作。
     */
    @Serializable
    data class Downloads(
        val gid: Long = -1L,
        val clearService: Boolean = false,
    ) : EhRoute

    /** 画廊列表（首页 / 订阅 / 热门 / 榜单 / 自定义搜索） */
    @Serializable
    data class GalleryList(val query: GalleryQuery = GalleryQuery()) : EhRoute

    /** 画廊详情；只带标识，标题/缩略图由 `galleryDetailCache` 补 */
    @Serializable
    data class GalleryDetail(
        val gid: Long = -1L,
        val token: String? = null,
        val page: Int = 0,
    ) : EhRoute

    /** 画廊信息（键值列表） */
    @Serializable
    data class GalleryInfo(val gid: Long = -1L) : EhRoute

    /** 画廊预览图（分页缩略图网格） */
    @Serializable
    data class GalleryPreviews(
        val gid: Long = -1L,
        val scrollTo: Int = -1,
    ) : EhRoute

    /** 画廊评论（含富文本编辑器） */
    @Serializable
    data class GalleryComments(
        val gid: Long = -1L,
        val apiUid: Long = -1L,
        val apiKey: String? = null,
        val token: String? = null,
    ) : EhRoute

    /** 后台任务进度 / 失败重试页（换 token 后跳详情） */
    @Serializable
    data class Progress(
        val gid: Long = -1L,
        val pToken: String? = null,
        val page: Int = 0,
    ) : EhRoute

    /**
     * 阅读器。
     *
     * [page] 是 0 基起始页；负数表示「沿用上次读到的位置」，由页源的 `startPage` 决定。
     * [source] 决定页数据从哪来，见 [ReaderSource]；压缩包走 [uri] 而不是 gid/token。
     */
    @Serializable
    data class Reader(
        val gid: Long = -1L,
        val token: String? = null,
        val page: Int = -1,
        val source: ReaderSource = ReaderSource.ONLINE,
        val uri: String? = null,
    ) : EhRoute

    /**
     * 设置首页。
     *
     * 首页、[About] 与全部分类子页都是纯 Compose，老的 `SettingsActivity` 已经删掉。
     */
    @Serializable
    data object Settings : EhRoute

    /**
     * 设置分类页。
     *
     * [page] 决定内容，九个分类子页都由 `SettingsCategoryScene` 就地渲染，见 [SettingsPage]。
     */
    @Serializable
    data class SettingsCategory(val page: SettingsPage) : EhRoute

    /** 关于页（版本、声明、源码与反馈链接） */
    @Serializable
    data object About : EhRoute
}

/**
 * 设置的全部子页。
 *
 * 这个枚举就是 [EhRoute.SettingsCategory] 的载荷：`SettingsCategoryScene` 的 `when`
 * 是穷尽的，加页时编译器会强制那里跟着补一条分支（不会再出现「点进去是空白壳」）。
 */
@Serializable
enum class SettingsPage {
    /** 已迁：`EhSettingsScreen` */
    EH,

    /** 已迁：`ReadSettingsScreen` */
    READ,

    /** 已迁：`PrivacySettingsScreen` */
    PRIVACY,

    DOWNLOAD,
    ADVANCED,

    /** UConfig 编辑器 */
    UCONFIG,

    /** 我的标签 */
    MY_TAGS,

    /** 屏蔽规则 */
    FILTER,

    /** 手势锁设置（从 Privacy 页进入） */
    SECURITY,
}

/**
 * 阅读器的页数据来源。
 *
 * 压缩包把 `Uri` 存成 [EhRoute.Reader.uri]（`@Serializable` 塞不进 `Uri` 本身），
 * 所以外部「用 EhViewer 打开压缩包」也能进 NavHost，不再单独起 Activity。
 */
@Serializable
enum class ReaderSource {
    /** 在线 / 缓存：走 SpiderQueen 读模式，已下载画廊它自己会先读本地 */
    ONLINE,

    /** 已下载：直接读下载目录，不发任何请求 */
    DOWNLOAD,

    /** 本地压缩包（zip / rar / 7z…）：按 [EhRoute.Reader.uri] 打开 */
    ARCHIVE,
}

/**
 * 当前页面是不是守卫链上的一跳（手势锁 / 登录 / 选站点）。
 *
 * 取代旧的 `SolidScene::class.java.isAssignableFrom(...)` 判断：所有页面都继承
 * `EhComposeScene`，而它又继承 `SolidScene`，所以那个判断恒为 true。
 * 按 route 判定是等价且不会骗人的写法。守卫期间宿主跳过剪贴板提示等行为。
 */
fun isSolidRoute(route: EhRoute?): Boolean = when (route) {
    is EhRoute.Security, is EhRoute.SignIn, is EhRoute.SelectSite -> true
    is EhRoute.GalleryList, is EhRoute.GalleryDetail, is EhRoute.GalleryInfo,
    is EhRoute.GalleryPreviews, is EhRoute.GalleryComments, is EhRoute.Progress,
    is EhRoute.Favorites, is EhRoute.History, is EhRoute.Downloads, is EhRoute.Reader,
    EhRoute.CookieSignIn, EhRoute.WebViewSignIn, EhRoute.Settings, EhRoute.About,
    is EhRoute.SettingsCategory,
    -> false
    null -> true
}

/**
 * 该页面是否解锁左侧抽屉（原 `BaseScene.needShowLeftDrawer()`）。
 *
 * 守卫链上的页面和「进度中转页」都不允许拉抽屉：前者是入口闸门，
 * 后者马上会被详情页替换掉。阅读器同理——翻页手势不能被抽屉抢走。
 */
val EhRoute.hasLeftDrawer: Boolean
    get() = when (this) {
        is EhRoute.Security, is EhRoute.SignIn, is EhRoute.SelectSite,
        EhRoute.CookieSignIn, EhRoute.WebViewSignIn,
        is EhRoute.Progress, is EhRoute.Reader,
        EhRoute.Settings, EhRoute.About, is EhRoute.SettingsCategory,
        -> false
        is EhRoute.GalleryList, is EhRoute.GalleryDetail, is EhRoute.GalleryInfo,
        is EhRoute.GalleryPreviews, is EhRoute.GalleryComments,
        is EhRoute.Favorites, is EhRoute.History, is EhRoute.Downloads,
        -> true
    }

/**
 * SINGLE_TASK 的匹配槽位：同一个槽位视为「同一页」。
 *
 * 绝大多数页面参数不同就是不同页面，槽位取 `toString()`；下载页是例外——
 * 通知栏带 `gid` 二次进入不该再叠一层，而应回到已有那份并换掉参数。
 */
val EhRoute.navSlot: String
    get() = when (this) {
        is EhRoute.Downloads -> "Downloads"
        else -> toString()
    }

/**
 * 该页面对应的抽屉勾选项（原 `BaseScene.getNavCheckedItem()`）。
 *
 * 列表页的勾选跟着查询模式走；页面内搜索换模式后 route 不会变（与旧行为一致：
 * 就地换内容、不换页面），那种情况由列表页自己把新的 resId 推给宿主。
 */
val EhRoute.navCheckedItem: Int
    @IdRes get() = when (this) {
        EhRoute.Favorites -> R.id.nav_favourite
        EhRoute.History -> R.id.nav_history
        is EhRoute.Downloads -> R.id.nav_downloads
        is EhRoute.GalleryList -> when (query.mode) {
            ListUrlBuilder.MODE_NORMAL -> R.id.nav_homepage
            ListUrlBuilder.MODE_SUBSCRIPTION -> R.id.nav_subscription
            ListUrlBuilder.MODE_WHATS_HOT -> R.id.nav_whats_hot
            ListUrlBuilder.MODE_TOPLIST -> R.id.nav_toplist
            else -> 0
        }
        EhRoute.Settings, is EhRoute.SettingsCategory -> R.id.nav_settings
        else -> 0
    }

/**
 * 画廊列表的查询条件。
 *
 * 对应旧实现里的 `ListUrlBuilder`（Parcelable，进程重建不安全）。`action` 为空表示
 * 「按 [GalleryQuery] 的字段拼查询」，对应旧协议里的 `action_list_url_builder`；
 * 其余取值是宿主入口的动作常量（首页 / 订阅 / 热门 / 榜单）。
 */
@Serializable
data class GalleryQuery(
    val action: String? = null,
    val mode: Int = ListUrlBuilder.MODE_NORMAL,
    val category: Int = EhUtils.NONE,
    val keyword: String? = null,
    val advanceSearch: Int = -1,
    val minRating: Int = -1,
    val pageFrom: Int = -1,
    val pageTo: Int = -1,
    val showExpunged: Boolean = false,
    val hash: String? = null,
)
