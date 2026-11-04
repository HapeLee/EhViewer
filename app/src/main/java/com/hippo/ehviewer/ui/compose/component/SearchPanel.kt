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
package com.hippo.ehviewer.ui.compose.component

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.AdvanceSearchFlags
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搜索面板提交后的结果。
 *
 * 校验失败时只回传字符串资源 ID，由调用方（Scene）用 `getString()` 转成提示，
 * 这样面板本身不依赖 Activity，也便于以后复用到别的宿主。
 */
sealed interface SearchCriteria {
    data class Success(
        val mode: Int,
        val category: Int,
        val keyword: String?,
        val language: Int,
        val advanceSearch: Int,
        val minRating: Int,
        val pageFrom: Int,
        val pageTo: Int,
    ) : SearchCriteria

    data class Failure(
        @StringRes val messageRes: Int,
    ) : SearchCriteria
}

/** 分类勾选项：`bit` 是 `EhUtils` 的分类位，`category` 里置位表示「包含」 */
private val CATEGORY_OPTIONS = listOf(
    R.string.doujinshi to EhUtils.DOUJINSHI,
    R.string.manga to EhUtils.MANGA,
    R.string.artist_cg to EhUtils.ARTIST_CG,
    R.string.game_cg to EhUtils.GAME_CG,
    R.string.western to EhUtils.WESTERN,
    R.string.non_h to EhUtils.NON_H,
    R.string.image_set to EhUtils.IMAGE_SET,
    R.string.cosplay to EhUtils.COSPLAY,
    R.string.asian_porn to EhUtils.ASIAN_PORN,
    R.string.misc to EhUtils.MISC,
)

/** 10 个分类位的并集，用来把 `EhUtils.NONE` 展开成「全部包含」 */
private val ALL_CATEGORY_BITS = CATEGORY_OPTIONS.fold(0) { acc, (_, bit) -> acc or bit }

/**
 * 搜索面板的可变状态。
 *
 * 由宿主 Screen 用 `remember` 持有，跨「展开搜索 → 收起 → 再展开」保持；
 * 提交时用 [buildCriteria] 一次性转成请求参数，避免把 `ListUrlBuilder` 的
 * 可变字段散落在 UI 里。
 */
@Stable
class SearchPanelState(
    searchMode: Int = ListUrlBuilder.MODE_NORMAL,
    category: Int = EhUtils.NONE,
    language: Int = -1,
    advanceSearch: Int = 0,
    minRating: Int = -1,
    pageFrom: String = "",
    pageTo: String = "",
) {
    /** 搜索模式（普通 / 订阅 / 上传者 / 标签）：入口带进来的默认值，可在面板第三行的 chip 里改 */
    var searchMode by mutableIntStateOf(searchMode)

    /** 排除位，与旧 `CategoryTable.category` 同语义 */
    var category by mutableIntStateOf(category)

    /** 语言过滤：`GalleryInfo.S_LANG_TAGS` 的下标，-1 表示不限 */
    var language by mutableIntStateOf(language)
    var advanceSearch by mutableIntStateOf(advanceSearch)
    var minRating by mutableIntStateOf(minRating)
    var pageFrom by mutableStateOf(pageFrom)
    var pageTo by mutableStateOf(pageTo)

    /**
     * `category` 是「包含」掩码，`EhUtils.NONE`(-1) 表示不过滤（等价于全部包含）。
     * 旧 `CategoryTable` 的勾选态是「排除」，这里反过来：chip 选中 = 包含。
     */
    fun isCategoryChecked(bit: Int): Boolean = category == EhUtils.NONE || (category and bit) != 0

    private fun normalizedCategory(): Int = if (category == EhUtils.NONE) ALL_CATEGORY_BITS else category

    /** 长按某个分类：其余分类统一取反，与旧 `CategoryTable.onLongClick` 一致 */
    fun reverseOthers(bit: Int) {
        val checked = isCategoryChecked(bit)
        var newCategory = if (checked) bit else 0
        for ((_, optionBit) in CATEGORY_OPTIONS) {
            if (optionBit == bit) continue
            if (!checked) {
                newCategory = newCategory or optionBit
            }
        }
        category = newCategory
    }

    fun toggleCategory(bit: Int) {
        val current = normalizedCategory()
        category = if (current and bit != 0) current and bit.inv() else current or bit
    }

    fun toggleAdvance(bit: Int) {
        advanceSearch = if (advanceSearch and bit != 0) advanceSearch and bit.inv() else advanceSearch or bit
    }

    fun hasAdvance(bit: Int): Boolean = advanceSearch and bit != 0

    /**
     * 生成提交参数（图片搜索不走这里，它在搜索栏里选完图就直接搜了）。
     *
     * 高级搜索不再单独提供开关（对齐参考实现的 `SearchFilter`）：只要填了任意一个
     * 高级条件（星级 / 页数 / 过滤开关），就自动带 `advsearch=1`。
     */
    fun buildCriteria(query: String?): SearchCriteria {
        // 与页数对话框一致：提交侧再收敛一次到 0..1000 / 0..2000，0 视作未填
        val from = (pageFrom.toIntOrNull() ?: 0).coerceIn(0, 1000).takeIf { it > 0 } ?: -1
        val to = (pageTo.toIntOrNull() ?: 0).coerceIn(0, 2000).takeIf { it > 0 } ?: -1
        val hasAdvance = advanceSearch != 0 || minRating != -1 || from != -1 || to != -1
        if (!hasAdvance) {
            return SearchCriteria.Success(
                mode = searchMode,
                category = category,
                keyword = query,
                language = language,
                advanceSearch = -1,
                minRating = -1,
                pageFrom = -1,
                pageTo = -1,
            )
        }
        // 页数对话框里已经就地拦过一次，这里是提交时的兜底
        val messageRes = validatePageRange(from, to)
        if (messageRes != 0) {
            return SearchCriteria.Failure(messageRes)
        }
        return SearchCriteria.Success(
            mode = searchMode,
            category = category,
            keyword = query,
            language = language,
            advanceSearch = advanceSearch,
            minRating = minRating,
            pageFrom = from,
            pageTo = to,
        )
    }
}

/**
 * 搜索条件面板：前两行与参考实现的 `SearchFilter` 一一对应——
 * 分类（`LazyRow`，已选中的排前面并带 `animateItem`）＋ 筛选行
 * （语言 / 最低评分 / 页数 / 过滤开关，整行横向滚动）。
 *
 * 第三行是本项目独有的搜索模式 chip（参考实现把模式放在搜索栏里）；
 * 「普通搜索 / 图片搜索」页签不再有：图片搜索从搜索栏右上角的图标进入。
 *
 * 分类 chip 仍保留旧 `CategoryTable` 的长按语义（其余分类取反），见 [CategoryChip]。
 */
@Composable
fun SearchPanel(
    state: SearchPanelState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CategoryRow(state)
        FilterRow(state)
        SearchModeRow(state)
    }
}

/** 第一行：分类，横滑；已选中的排在最前面，位置变化用 animateItem 播一下动画 */
@Composable
private fun CategoryRow(state: SearchPanelState) {
    val categories = remember(state.category) {
        CATEGORY_OPTIONS.sortedBy { !state.isCategoryChecked(it.second) }
    }
    LazyRow(
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 与参考实现同样的 workaround：首项动画会抽一下，先塞一个空 item
        item {}
        items(categories, key = { it.second }) { (labelRes, bit) ->
            CategoryChip(
                label = stringResource(labelRes),
                checked = state.isCategoryChecked(bit),
                onClick = { state.toggleCategory(bit) },
                onLongClick = { state.reverseOthers(bit) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * 第二行：语言、最低评分两枚下拉 chip + 页数 chip，后面跟一串过滤开关，整行横向滚动。
 *
 * 对应参考实现 `SearchFilter` 的第二行。下拉 chip 未选时显示 label、选中后直接显示值，
 * 末尾三角提示可以展开；页数是「点开对话框填区间」而不是两个常驻输入框；chip 本身不带勾选图标。
 */
@Composable
private fun FilterRow(state: SearchPanelState) {
    val languageLabels = languageLabels()
    val ratingLabels = listOf(stringResource(R.string.search_min_rating_none)) +
        STAR_RATINGS.map { (labelRes, _) -> stringResource(labelRes) }
    val disableFilter = stringResource(R.string.search_sf)
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownChip(
            label = stringResource(R.string.key_language),
            options = languageLabels,
            selectedIndex = (state.language + 1).coerceIn(0, languageLabels.lastIndex),
            onSelected = { state.language = it - 1 },
        )
        DropdownChip(
            // `search_sr` 自带冒号，作为 chip label 时去掉
            label = stringResource(R.string.search_sr).trimEnd(':', '：', ' '),
            options = ratingLabels,
            // 0 = 不限，1..n 对应 2..5 星
            selectedIndex = if (state.minRating in 2..5) state.minRating - 1 else 0,
            onSelected = { state.minRating = if (it == 0) -1 else it + 1 },
        )
        PageRangeChip(state)
        ToggleChip(
            label = stringResource(R.string.search_sh),
            checked = state.hasAdvance(AdvanceSearchFlags.SH),
            onClick = { state.toggleAdvance(AdvanceSearchFlags.SH) },
        )
        ToggleChip(
            label = stringResource(R.string.search_sto),
            checked = state.hasAdvance(AdvanceSearchFlags.STO),
            onClick = { state.toggleAdvance(AdvanceSearchFlags.STO) },
        )
        ToggleChip(
            label = disableFilter + stringResource(R.string.search_sfl),
            checked = state.hasAdvance(AdvanceSearchFlags.SFL),
            onClick = { state.toggleAdvance(AdvanceSearchFlags.SFL) },
        )
        ToggleChip(
            label = disableFilter + stringResource(R.string.search_sfu),
            checked = state.hasAdvance(AdvanceSearchFlags.SFU),
            onClick = { state.toggleAdvance(AdvanceSearchFlags.SFU) },
        )
        ToggleChip(
            label = disableFilter + stringResource(R.string.search_sft),
            checked = state.hasAdvance(AdvanceSearchFlags.SFT),
            onClick = { state.toggleAdvance(AdvanceSearchFlags.SFT) },
        )
    }
}

/** 第三行：搜索模式（普通 / 订阅 / 上传者 / 标签），横滑，无标题 */
@Composable
private fun SearchModeRow(state: SearchPanelState) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(SEARCH_MODES, key = { it.second }) { (labelRes, mode) ->
            FilterChip(
                selected = state.searchMode == mode,
                onClick = { state.searchMode = mode },
                label = { Text(text = stringResource(labelRes)) },
            )
        }
    }
}

/** 语言下拉项：首项「不限」，其余取 `GalleryInfo.S_LANG_TAGS` 的标签名（开了标签翻译就用译文） */
@Composable
private fun languageLabels(): List<String> {
    val any = stringResource(R.string.search_min_rating_none)
    val translateBySetting by Settings.valueFlow(Settings.KEY_SHOW_TAG_TRANSLATIONS, false)
        .collectAsState(false)
    val tagDbVersion by EhTagDatabase.versionFlow.collectAsState(0)
    val translate = translateBySetting && tagDbVersion > 0
    return buildList(GalleryInfo.S_LANG_TAGS.size + 1) {
        add(any)
        GalleryInfo.S_LANG_TAGS.forEach { tag ->
            val name = tag.substringAfter(':')
            val translated = if (translate) EhTagDatabase.getTranslation("l", name) else null
            add(translated ?: name.replaceFirstChar { it.uppercase() })
        }
    }
}

/** 页数区间的单位后缀：参考实现直接写死 "P"（`search_sp_suffix` 在多数语言里是空串） */
private const val PAGE_UNIT = "P"

private val SEARCH_MODES = listOf(
    R.string.search_normal_search to ListUrlBuilder.MODE_NORMAL,
    R.string.search_subscription_search to ListUrlBuilder.MODE_SUBSCRIPTION,
    R.string.search_specify_uploader to ListUrlBuilder.MODE_UPLOADER,
    R.string.search_specify_tag to ListUrlBuilder.MODE_TAG,
)

/**
 * 页数 chip：点开对话框填区间，chip 上直接显示当前区间。
 *
 * 与参考实现一致——页数不再是两个常驻输入框，只在打开对话框时出现；
 * 没填时显示「页数」，只填一端时显示 `10+ P` / `900- P`。
 */
@Composable
private fun PageRangeChip(state: SearchPanelState) {
    var showDialog by remember { mutableStateOf(false) }
    val from = state.pageFrom.toIntOrNull() ?: 0
    val to = state.pageTo.toIntOrNull() ?: 0
    val text = when {
        from > 0 && to > 0 -> "$from - $to $PAGE_UNIT"
        from > 0 -> "$from+ $PAGE_UNIT"
        to > 0 -> "$to- $PAGE_UNIT"
        else -> stringResource(R.string.key_pages)
    }
    FilterChip(
        selected = from > 0 || to > 0,
        onClick = { showDialog = true },
        label = { Text(text = text) },
    )
    if (showDialog) {
        PageRangeDialog(state = state, onDismiss = { showDialog = false })
    }
}

/**
 * 页数区间对话框：两个数字输入框 + 就地校验，排版照搬参考实现。
 *
 * 校验不通过时不关闭对话框，直接在下面挂一条 `ListItem` 样式的红字提示
 * （文案与提交时的校验共用 [validatePageRange]），填错了不用先点确定再被弹回来。
 */
@Composable
private fun PageRangeDialog(
    state: SearchPanelState,
    onDismiss: () -> Unit,
) {
    var fromText by remember { mutableStateOf(state.pageFrom) }
    var toText by remember { mutableStateOf(state.pageTo) }
    var errorRes by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.key_pages)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        value = fromText,
                        onValueChange = {
                            fromText = it.filter(Char::isDigit).take(4)
                            errorRes = 0
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        singleLine = true,
                        isError = errorRes != 0,
                    )
                    Text(text = stringResource(R.string.search_sp_to))
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        value = toText,
                        onValueChange = {
                            toText = it.filter(Char::isDigit).take(4)
                            errorRes = 0
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        singleLine = true,
                        isError = errorRes != 0,
                    )
                }
                if (errorRes != 0) {
                    ListItem(
                        leadingContent = {
                            Icon(imageVector = Icons.Filled.Info, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        content = {
                            Text(
                                text = stringResource(errorRes),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val from = (fromText.toIntOrNull() ?: 0).coerceIn(0, 1000)
                    val to = (toText.toIntOrNull() ?: 0).coerceIn(0, 2000)
                    val messageRes = validatePageRange(from, to)
                    if (messageRes != 0) {
                        errorRes = messageRes
                    } else {
                        state.pageFrom = from.takeIf { it > 0 }?.toString().orEmpty()
                        state.pageTo = to.takeIf { it > 0 }?.toString().orEmpty()
                        onDismiss()
                    }
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

/**
 * 页数区间校验，照搬参考实现 `SearchFilter` 里的 `ensure` 条件：
 * 只填最大值时要求 ≥ 10；两端都填时最小值不得超过 `min(max / 2, max - 20)`。
 * 返回 0 表示通过，否则是提示文案的资源 ID。
 */
internal fun validatePageRange(fromRaw: Int, toRaw: Int): Int {
    val from = fromRaw.takeIf { it > 0 } ?: 0
    val to = toRaw.takeIf { it > 0 } ?: 0
    return when {
        to == 0 -> 0
        from == 0 -> if (to >= 10) 0 else R.string.search_sp_err1
        from <= (to / 2).coerceAtMost(to - 20) -> 0
        else -> R.string.search_sp_err2
    }
}

/**
 * 分类 chip：M3 的 [FilterChip]，额外保留旧 `CategoryTable` 的长按语义（其余分类取反）。
 *
 * [FilterChip] 自己接了点击手势，外面再套 `combinedClickable` 是拿不到长按的（内层的
 * clickable 会先消费按下事件），所以长按走 [androidx.compose.foundation.interaction.InteractionSource]：
 * 按下满 `longPressTimeoutMillis` 就触发，同时置位让随后抬起时的 `onClick` 失效，
 * 避免「长按把其余项反选完，抬手又把自己也反选了」。
 */
@Composable
private fun CategoryChip(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    // 读在点击回调里（而不是组合期），所以抬手时拿到的一定是最新值
    var handledByLongPress by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource, longPressTimeout) {
        var job: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    handledByLongPress = false
                    job = launch {
                        delay(longPressTimeout)
                        handledByLongPress = true
                        currentOnLongClick()
                    }
                }
                else -> {
                    job?.cancel()
                    job = null
                }
            }
        }
    }
    FilterChip(
        selected = checked,
        onClick = { if (!handledByLongPress) onClick() },
        label = { Text(text = label) },
        interactionSource = interactionSource,
        modifier = modifier,
    )
}

/** 多选开关 chip：只靠选中态着色，不带勾选图标（与参考实现一致） */
@Composable
private fun ToggleChip(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = checked,
        onClick = onClick,
        label = { Text(text = label) },
        modifier = modifier,
    )
}

/**
 * 下拉 chip：点开在菜单里选一项，chip 上直接显示当前值，末尾一个朝下的三角。
 *
 * 与参考实现的 `DropdownFilterChip` 完全一致：`ExposedDropdownMenuBox` + `menuAnchor`，
 * 菜单贴着 chip 展开且不跟随 anchor 宽度；未选（下标 0）时显示 label，选中后直接显示值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownChip(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        FilterChip(
            selected = selectedIndex != 0,
            onClick = {},
            label = {
                Text(text = if (selectedIndex == 0) label else options[selectedIndex])
            },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            matchAnchorWidth = false,
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(text = option) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

private val STAR_RATINGS = listOf(
    R.string.star_2 to 2,
    R.string.star_3 to 3,
    R.string.star_4 to 4,
    R.string.star_5 to 5,
)
