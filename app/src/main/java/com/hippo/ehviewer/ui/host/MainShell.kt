/*
 * Copyright 2026 EhViewer contributors
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.ui.host

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.ExperimentalNavigationEventApi
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import coil3.compose.AsyncImage
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.launch

/**
 * 主界面外壳（原 `activity_main.xml` + `nav_header_main.xml` + `menu/nav_drawer_main.xml`）。
 *
 * 三层结构一一对应旧 XML：
 * - `DrawerLayout` → [DismissibleNavigationDrawer]：左侧导航抽屉，`drawerEnabled` 就是旧的
 *   `setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED/UNLOCKED)`（守卫链、进度页、阅读器、设置页锁住）；
 * - `NavigationView` + header → [MainDrawer]：头像 / 显示名 / 夜间模式按钮 + 8 个条目；
 * - `CoordinatorLayout(@id/snackbar)` → [SnackbarHost]：页面与宿主共用一个 `SnackbarHostState`。
 *
 * 抽屉用的是 M3 的 dismissible 标准抽屉（对应 `DismissibleNavigationDrawerSample`），
 * 和这个文件此前用的 [androidx.compose.material3.ModalNavigationDrawer] 有两处实质差异，
 * 都来自「抽屉是布局的一部分」而不是「浮在内容上面的弹层」：
 * - **没有遮罩**：内容不变暗、也仍然可点，`scrimColor` / 点击外部关闭这两件事都没有了，
 *   只剩拖拽、返回键、代码 `close()` 三种关法（条目点击后由 `MainActivity.onNavSelect` 关）；
 * - **内容被推开**：M3 的实现里内容 X 坐标是 `抽屉宽度 + 抽屉当前 offset`，抽屉展开时整个
 *   页面往右让出抽屉的宽度。所以抽屉宽度直接决定"页面还剩多少可见"——真的要在手机竖屏上
 *   长期使用，得把 [MainDrawer] 的宽度收到 280dp 上下，否则页面对 360dp 宽的抽屉等于被顶出屏幕。
 *
 * 除上面那条宽度提醒外，状态栏 inset / 条目选中高亮仍用 M3 默认（`DrawerSheet` 内部是
 * `sizeIn(minWidth = 240.dp, maxWidth = 360.dp)`，要改宽度就在 [MainDrawer] 的 sheet 上加 `width`）。旧的
 * `IgnoreFitsSystemWindowsFullyDraggableDrawerContentLayout` 与 `itemShapeInset*`
 * 那批 XML 微调在 M3 里没有对应项，也不再需要。
 *
 * 主题相关的一点：这里不再是 View 树，拿不到 XML 主题的 `?android:attr/textColorPrimary`；
 * 字号 / 颜色一律从 `MaterialTheme` 取（`LocalContentColor` 就是 XML 时代的
 * `textColorPrimary` 对应物）。XML 主题只留给启动画面和还没出壳的 View。
 */
@Composable
fun MainShell(
    drawerState: DrawerState,
    drawerEnabled: Boolean,
    @IdRes checkedNavId: Int,
    /** 只是「重读 Settings 里的头像/昵称」的触发器，改这个值就会重组 */
    profileVersion: Int,
    snackbarHostState: SnackbarHostState,
    onNavSelect: (Int) -> Unit,
    onToggleNightMode: () -> Unit,
    onProfileClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        drawerContent = {
            MainDrawer(
                checkedNavId = checkedNavId,
                profileVersion = profileVersion,
                onNavSelect = onNavSelect,
                onToggleNightMode = onToggleNightMode,
                onProfileClick = onProfileClick,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 对应旧 XML 最外层的 fitsSystemWindowsInsets="start|end"：
                // 横屏刘海 / 侧边手势条不压内容；纵向仍由各页自己的 Scaffold 处理
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
        ) {
            content()
            // 抽屉开着时返回键 / 预测性返回手势先关抽屉（原 `onBackPressed` 的分支）
            DrawerBackHandler(drawerState = drawerState)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
}

/**
 * 抽屉开着时的返回处理。
 *
 * 这里不能用普通的 `BackHandler`：开了 `enableOnBackInvokedCallback` 之后，返回统一走
 * `androidx.navigationevent` 的 dispatcher，同优先级下后注册的先派发，而页面里的
 * `BackHandler`（退搜索、关评论框）与 `NavDisplay` 的弹栈回调都比外壳注册得晚，
 * 外壳抢不过它们——实测会觉得「返回键关不掉抽屉，还把页面弹了」。所以这里显式用
 * `PRIORITY_OVERLAY` 注册，让抽屉这一层压在页面之上。
 */
@OptIn(ExperimentalNavigationEventApi::class)
@Composable
private fun DrawerBackHandler(drawerState: DrawerState) {
    val owner = LocalNavigationEventDispatcherOwner.current ?: return
    val scope = rememberCoroutineScope()
    val handler = remember(owner) {
        object : NavigationEventHandler<NavigationEventInfo>(object : NavigationEventInfo() {}, drawerState.isOpen) {
            override fun onBackCompleted() {
                // DrawerState.close() 带动画，要有帧时钟；组合里的 scope 自带
                scope.launch { drawerState.close() }
            }
        }
    }
    DisposableEffect(owner, handler) {
        owner.navigationEventDispatcher.addHandler(handler, NavigationEventDispatcher.PRIORITY_OVERLAY)
        onDispose { handler.remove() }
    }
    // 在组合里读一次：抽屉开合会触发重组，SideEffect 才能把最新的开关状态写回 handler
    // （只在 lambda 里读的话不会建立订阅，handler 会一直保持「可用」，把所有返回都吃掉）
    val isOpen = drawerState.isOpen
    SideEffect { handler.isBackEnabled = isOpen }
}

@Composable
private fun MainDrawer(
    @IdRes checkedNavId: Int,
    profileVersion: Int,
    onNavSelect: (Int) -> Unit,
    onToggleNightMode: () -> Unit,
    onProfileClick: () -> Unit,
) {
    // 走不带 drawerState 的重载：带状态的那个会自己注册 `DrawerPredictiveBackHandler`，
    // 和本文件的 [DrawerBackHandler]（PRIORITY_OVERLAY）抢同一次返回，动画只会被吃掉一半。
    // 返回键的处理统一留在外壳这一层。
    DismissibleDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DrawerHeader(
                profileVersion = profileVersion,
                onToggleNightMode = onToggleNightMode,
                onProfileClick = onProfileClick,
            )
            NAV_ENTRIES.forEach { entry ->
                NavigationDrawerItem(
                    label = { Text(text = stringResource(entry.titleRes)) },
                    icon = {
                        Icon(imageVector = entry.icon, contentDescription = null)
                    },
                    selected = checkedNavId == entry.id,
                    onClick = { onNavSelect(entry.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}

/**
 * 抽屉头：圆形头像 + 显示名 + 夜间模式按钮（对应 `nav_header_main.xml`）。
 *
 * 不再铺那张低多边形熊猫底图——直接用抽屉自己的容器色，所以文字 / 图标颜色也从
 * 「压在图上用的白色」改成主题的 `onSurfaceVariant` / `LocalContentColor`。
 */
@Composable
private fun DrawerHeader(
    profileVersion: Int,
    onToggleNightMode: () -> Unit,
    onProfileClick: () -> Unit,
) {
    // profileVersion 是「重读一次」的信号：Settings 不是 Compose 状态，靠它触发重组
    val avatar = remember(profileVersion) { Settings.avatar }
    val savedName = remember(profileVersion) { Settings.displayName?.takeIf { it.isNotEmpty() } }
    val displayName = savedName ?: stringResource(R.string.default_display_name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatarModifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(onClick = onProfileClick)
        if (avatar.isNullOrEmpty()) {
            Image(
                painter = painterResource(R.drawable.default_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = avatarModifier,
            )
        } else {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = avatarModifier,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleNightMode) {
            Icon(
                imageVector = Icons.Filled.DarkMode,
                contentDescription = stringResource(R.string.night_mode),
                tint = LocalContentColor.current,
            )
        }
    }
}

/**
 * 抽屉条目。
 *
 * id 与 `EhRoute.navCheckedItem` / `GalleryListViewModel.navCheckedRes` 用的是同一套
 * （`values/ids.xml`）；顺序与旧 `menu/nav_drawer_main.xml` 一致——前四项是列表页的四种
 * 查询模式，后面四个是独立页面。
 */
private class NavEntry(
    @IdRes val id: Int,
    val icon: ImageVector,
    @StringRes val titleRes: Int,
)

private val NAV_ENTRIES = listOf(
    NavEntry(R.id.nav_homepage, Icons.Filled.Home, R.string.homepage),
    NavEntry(R.id.nav_subscription, Icons.Filled.RssFeed, R.string.subscription),
    NavEntry(R.id.nav_whats_hot, Icons.Filled.Whatshot, R.string.whats_hot),
    NavEntry(R.id.nav_toplist, Icons.Filled.FormatListNumbered, R.string.toplist),
    NavEntry(R.id.nav_favourite, Icons.Filled.Favorite, R.string.favourite),
    NavEntry(R.id.nav_history, Icons.Filled.History, R.string.history),
    NavEntry(R.id.nav_downloads, Icons.Filled.Download, R.string.downloads),
    NavEntry(R.id.nav_settings, Icons.Filled.Settings, R.string.settings),
)
