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

import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import kotlinx.serialization.Serializable

/**
 * Navigation 3 的路由表。
 *
 * P7-a 起这里同时是「唯一导航入口的入参类型」：调用方不再拼 `Bundle` + `Announcer`，
 * 而是构造一个 route 交给 [EhNavigator]。参数用**字段**表达（而不是 Parcelable），
 * 所以路由可序列化、可深链、可在没有 Activity 的环境里断言。
 *
 * 迁移期 route 与旧 `Bundle` 的互转集中在 [com.hippo.ehviewer.ui.compose.nav.EhRoutes]，
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

    /** Compose 阅读器（当前承接压缩包画廊） */
    @Serializable
    data class Reader(
        val gid: Long = -1L,
        val page: Int = 0,
        val mode: String? = null,
    ) : EhRoute
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
