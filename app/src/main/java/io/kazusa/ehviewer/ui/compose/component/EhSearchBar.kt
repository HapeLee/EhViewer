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
package io.kazusa.ehviewer.ui.compose.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.ExpandedDockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import io.kazusa.ehviewer.EhApplication.Companion.searchDatabase
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 状态栏压底的不透明度：浅色底要更实（0.9）才压得住正文，深色底 0.5 就够 */
@Composable
private fun statusBarScrimAlpha(): Float = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.5f else 0.9f

/** 单条联想：搜索历史可删除，标签点一下补全到当前词 */
enum class SearchSuggestionType { HISTORY, TAG }

data class SearchSuggestionUi(
    val keyword: String,
    val hint: String?,
    val type: SearchSuggestionType,
)

/**
 * 列表页外壳：**顶栏本身就是一个 M3 搜索条**，结构照抄参考项目 `EHz/EhViewer` 的
 * `SearchBarScreen`（只是把 material3 1.3 的 `SearchBar(expanded = …)` 换成了 1.5 的
 * `SearchBar(state = …)` + `ExpandedDockedSearchBar`）：
 *
 * - 收起态：条浮在页面顶部，占位符是页面标题，左侧抽屉按钮，右侧是宿主给的 [trailingIcon]；
 *   列表滚动时通过 [searchBarOffsetY] 把条整体推上去（参考项目同款）；
 * - 展开态：条长成 docked 表，内容由 [expandedContent] 提供（搜索面板 + 联想列表）；
 * - 展开状态是受控的：[expanded] 由宿主持有，这里翻译成搜索条的动画；用户点输入框展开、
 *   点空白收起时通过 [onExpandedChange] 回调宿主。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EhSearchBarPage(
    title: String,
    hint: String,
    query: String,
    expanded: Boolean,
    onQueryChange: (String) -> Unit,
    onApplySearch: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenImageSearch: (() -> Unit)? = null,
    searchBarOffsetY: () -> Int = { 0 },
    trailingIcon: @Composable () -> Unit = {},
    expandedContent: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(query)
    val latestExpanded by rememberUpdatedState(expanded)
    val isExpanded = searchBarState.currentValue == SearchBarValue.Expanded

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(onQueryChange)
    }
    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }

    // 宿主状态 → 搜索条
    LaunchedEffect(expanded) {
        when {
            expanded && searchBarState.targetValue != SearchBarValue.Expanded ->
                searchBarState.animateToExpanded()
            !expanded && searchBarState.targetValue != SearchBarValue.Collapsed ->
                searchBarState.animateToCollapsed()
        }
    }
    // 搜索条 → 宿主
    LaunchedEffect(searchBarState) {
        snapshotFlow { searchBarState.targetValue }.collect { value ->
            val nowExpanded = value == SearchBarValue.Expanded
            if (nowExpanded != latestExpanded) onExpandedChange(nowExpanded)
        }
    }

    fun applySearch() {
        val text = textFieldState.text.trim().toString()
        onExpandedChange(false)
        // 空词也要回传：清空输入框再按搜索 = 取消当前过滤条件
        onApplySearch(text)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 参考项目的 workaround：非触摸模式下 SearchBar 会反复 refocus 导致退不出去
        Box(Modifier.size(1.dp).focusable())

        val baseTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            EhSearchBarTopPadding
        val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            // 正文顶部留出搜索条的高度。留白交给列表自己的 contentPadding，
            // 列表铺满窗口，滚动时条目会从搜索条下面穿过去，不会剩下空白带
            content(
                PaddingValues(
                    top = baseTopPadding,
                    bottom = bottomPadding,
                ),
            )
        }

        val inputField = @Composable {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = { applySearch() },
                modifier = Modifier
                    .widthIn(max = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth))
                    .fillMaxWidth(),
                placeholder = {
                    // 收起时用页面标题当占位符，展开时换成搜索 hint
                    Text(
                        text = if (isExpanded) hint else title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    if (isExpanded) {
                        IconButton(onClick = { onExpandedChange(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    } else {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = null)
                        }
                    }
                },
                trailingIcon = {
                    if (isExpanded) {
                        AnimatedContent(targetState = textFieldState.text.isNotEmpty()) { hasText ->
                            if (hasText) {
                                IconButton(onClick = { textFieldState.clearText() }) {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                }
                            } else {
                                onOpenImageSearch?.let { onOpenImageSearch ->
                                    IconButton(onClick = onOpenImageSearch) {
                                        Icon(Icons.Filled.ImageSearch, contentDescription = null)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            trailingIcon()
                        }
                    }
                },
            )
        }

        // 正文会跟着搜索条一起滚到状态栏底下，这里压一层背景色，别让列表从时钟图标下穿过去
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                // 半透明：和参考项目一样，让正文在状态栏下面若隐若现，而不是糊死一条底色。
                // 明暗按当前色板算（而不是 isSystemInDarkTheme），阅读器那种把主题钉死的
                // 场景才不会跟实际底色拧着来
                .background(MaterialTheme.colorScheme.background.copy(alpha = statusBarScrimAlpha())),
        )

        SearchBar(
            state = searchBarState,
            inputField = inputField,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(vertical = 8.dp)
                // 列表滚动时整条往上推，和参考项目一致
                .offset { IntOffset(0, if (expanded) 0 else searchBarOffsetY()) }
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        )
        ExpandedDockedSearchBar(
            state = searchBarState,
            inputField = inputField,
        ) {
            expandedContent()
        }
    }
}

/**
 * 展开层里的联想列表：搜索面板（[leading]）＋链接建议（[header]）＋历史/标签。
 *
 * 列表行照抄参考项目：透明容器、可删除的历史在右侧给一个叉、可点击行带 `animateItem`。
 * 底部留 `safeDrawing` inset，键盘弹起时最后几条也能滚出来。
 */
@Composable
fun SearchSuggestionList(
    suggestions: List<SearchSuggestionUi>,
    onClick: (SearchSuggestionUi) -> Unit,
    onDelete: (SearchSuggestionUi) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
    ) {
        leading?.let { item(key = "leading") { it() } }
        header?.let { item(key = "header") { it() } }
        itemsIndexed(
            items = suggestions,
            key = { index, suggestion -> "${suggestion.type.name}:${suggestion.keyword}:$index" },
        ) { _, suggestion ->
            ListItem(
                headlineContent = { Text(suggestion.keyword) },
                supportingContent = suggestion.hint?.let { hint -> { Text(hint) } },
                leadingContent = when (suggestion.type) {
                    SearchSuggestionType.HISTORY -> null
                    SearchSuggestionType.TAG -> {
                        { Icon(Icons.Filled.Sell, contentDescription = null) }
                    }
                },
                trailingContent = if (suggestion.type == SearchSuggestionType.HISTORY) {
                    {
                        IconButton(onClick = { onDelete(suggestion) }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                } else {
                    null
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clickable { onClick(suggestion) }
                    .animateItem(),
            )
        }
    }
}

/** 「粘贴的是画廊链接」那一条，宿主放在联想列表头部 */
@Composable
fun UrlSuggestionListItem(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

/**
 * 只靠搜索历史做联想的展开层内容。
 *
 * 画廊列表页的联想里还有标签库和筛选面板（那边由自己的 VM 提供），
 * 收藏 / 历史 / 下载这几页没有标签可联想，只回放最近搜过的词就够用了。
 * 空白输入框也会拉出最近的历史（`getSuggestions("")` 的语义），不会出现一片空白。
 */
@Composable
fun SearchHistorySuggestionList(
    query: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    limit: Int = 10,
    leading: (@Composable () -> Unit)? = null,
) {
    var suggestions by remember { mutableStateOf(emptyList<SearchSuggestionUi>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, limit) {
        delay(SEARCH_HISTORY_DEBOUNCE_MS)
        suggestions = withContext(Dispatchers.IO) {
            runCatching { searchDatabase.getSuggestions(query, limit) }
                .getOrNull()
                .orEmpty()
                .map { SearchSuggestionUi(it, null, SearchSuggestionType.HISTORY) }
        }
    }
    SearchSuggestionList(
        suggestions = suggestions,
        onClick = { onPick(it.keyword) },
        onDelete = { suggestion ->
            suggestions = suggestions.filterNot { it.keyword == suggestion.keyword }
            scope.launch(Dispatchers.IO) {
                runCatching { searchDatabase.deleteQuery(suggestion.keyword) }
            }
        },
        modifier = modifier,
        leading = leading,
    )
}

/**
 * 搜索条随列表滚动收缩、FAB 随滚动隐藏的状态机。
 *
 * 四个列表页（画廊 / 收藏 / 历史 / 下载）用的是同一套行为，抄来抄去容易走形，收在这里：
 * - [offsetY] 是搜索条的上推位移（负数），上限是「状态栏 + 搜索条高度」，
 *   与 [EhSearchBarPage] 给正文留的顶部留白一致；
 * - [fabHidden] 在向下滚时置 true、向上滚（或回到顶部）时置 false，宿主拿它控制 FAB；
 * - [connection] 只读不消费（永远返回 `Offset.Zero`），纵向手势仍然归列表自己。
 */
@Stable
class SearchBarScrollState(
    private val touchSlop: Float,
    private val maxCollapsePx: Int,
) {
    var offsetY by mutableIntStateOf(0)
    var fabHidden by mutableStateOf(false)

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val dy = -consumed.y
            if (dy >= touchSlop) {
                fabHidden = true
            } else if (dy <= -touchSlop / 2) {
                fabHidden = false
            }
            offsetY = (offsetY - dy).roundToInt().coerceIn(-maxCollapsePx, 0)
            return Offset.Zero // 位移只影响搜索条，手势从不在这里被吃掉
        }
    }

    /** 切标签 / 换页 / 展开搜索条时把位移复位；[hideFab] 决定顺带把 FAB 藏起来 */
    fun reset(hideFab: Boolean = false) {
        offsetY = 0
        fabHidden = hideFab
    }
}

@Composable
fun rememberSearchBarScrollState(): SearchBarScrollState {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val maxCollapsePx = WindowInsets.statusBars.getTop(density) +
        with(density) { EhSearchBarTopPadding.roundToPx() }
    return remember(viewConfiguration, maxCollapsePx) {
        SearchBarScrollState(viewConfiguration.touchSlop, maxCollapsePx)
    }
}

/** 搜索条自身占的高度（InputField + 上下 8dp padding），宿主算滚动位移时要用 */
val EhSearchBarTopPadding = SearchBarDefaults.InputFieldHeight + 16.dp

private val M3SearchBarMaxWidth = 720.dp
private val SearchBarHorizontalPadding = 16.dp
private const val SEARCH_HISTORY_DEBOUNCE_MS = 120L
