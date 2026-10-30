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

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.widget.AdvanceSearchTable

/** 搜索面板的两个页签，取值与旧 `SearchLayout.SEARCH_MODE_*` 对齐 */
object SearchPanelTab {
    const val NORMAL = 0
    const val IMAGE = 1
}

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
    tab: Int = SearchPanelTab.NORMAL,
    searchMode: Int = ListUrlBuilder.MODE_NORMAL,
    category: Int = EhUtils.NONE,
    advanceEnabled: Boolean = false,
    advanceSearch: Int = 0,
    minRating: Int = -1,
    pageFrom: String = "",
    pageTo: String = "",
    imageUri: Uri? = null,
) {
    var tab by mutableIntStateOf(tab)
    var searchMode by mutableIntStateOf(searchMode)

    /** 排除位，与旧 `CategoryTable.category` 同语义 */
    var category by mutableIntStateOf(category)
    var advanceEnabled by mutableStateOf(advanceEnabled)
    var advanceSearch by mutableIntStateOf(advanceSearch)
    var minRating by mutableIntStateOf(minRating)
    var pageFrom by mutableStateOf(pageFrom)
    var pageTo by mutableStateOf(pageTo)
    var imageUri by mutableStateOf(imageUri)

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
     * 生成提交参数。图片搜索的 `hash` 由调用方补，因为算 SHA-1 需要 Context/UniFile。
     */
    fun buildCriteria(query: String?): SearchCriteria {
        if (tab == SearchPanelTab.IMAGE) {
            if (imageUri == null) {
                return SearchCriteria.Failure(R.string.select_image_first)
            }
            return SearchCriteria.Success(
                mode = ListUrlBuilder.MODE_IMAGE_SEARCH,
                category = EhUtils.NONE,
                keyword = null,
                advanceSearch = -1,
                minRating = -1,
                pageFrom = -1,
                pageTo = -1,
            )
        }
        if (!advanceEnabled) {
            return SearchCriteria.Success(
                mode = searchMode,
                category = category,
                keyword = query,
                advanceSearch = -1,
                minRating = -1,
                pageFrom = -1,
                pageTo = -1,
            )
        }
        val from = pageFrom.toIntOrNull() ?: -1
        val to = pageTo.toIntOrNull() ?: -1
        val messageRes = when {
            from != -1 && from > 1000 -> R.string.search_sp_err0
            to != -1 && to < 10 -> R.string.search_sp_err1
            from != -1 && to != -1 && to - from < 20 -> R.string.search_sp_err2
            from != -1 && to != -1 && from.toFloat() / to > 0.5f -> R.string.search_sp_err3
            else -> 0
        }
        if (messageRes != 0) {
            return SearchCriteria.Failure(messageRes)
        }
        return SearchCriteria.Success(
            mode = searchMode,
            category = category,
            keyword = query,
            advanceSearch = advanceSearch,
            minRating = minRating,
            pageFrom = from,
            pageTo = to,
        )
    }
}

/**
 * 搜索条件面板（原 `SearchLayout` + `search_normal.xml` + `widget_advance_search_table.xml`
 * + `widget_image_search.xml` 的 Compose 版本）。
 *
 * 与旧实现的差异：分类表从 5 行 2 列的 `CheckTextView` 表改成 `FlowRow` 排布的 chip，
 * 语义完全保留（勾选=包含，长按=其余全部取反）；最低评分从 `Spinner` 改成 5 个 chip。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPanel(
    state: SearchPanelState,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSearchModeTip by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.tab == SearchPanelTab.NORMAL,
                onClick = { state.tab = SearchPanelTab.NORMAL },
                label = { Text(stringResource(R.string.search_normal)) },
            )
            FilterChip(
                selected = state.tab == SearchPanelTab.IMAGE,
                onClick = { state.tab = SearchPanelTab.IMAGE },
                label = { Text(stringResource(R.string.search_image)) },
            )
        }
        Spacer(Modifier.height(12.dp))

        if (state.tab == SearchPanelTab.NORMAL) {
            SectionTitle(R.string.search_category)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORY_OPTIONS.forEach { (labelRes, bit) ->
                    CheckableChip(
                        label = stringResource(labelRes),
                        checked = state.isCategoryChecked(bit),
                        onClick = { state.toggleCategory(bit) },
                        onLongClick = { state.reverseOthers(bit) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(R.string.search_normal, Modifier.weight(1f))
                IconButton(onClick = { showSearchModeTip = true }) {
                    Icon(
                        painter = painterResource(R.drawable.v_help_circle_x24),
                        contentDescription = stringResource(R.string.search_tip),
                    )
                }
            }
            FlowRow(
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SEARCH_MODES.forEach { (labelRes, mode) ->
                    RadioRow(
                        label = stringResource(labelRes),
                        selected = state.searchMode == mode,
                        onClick = { state.searchMode = mode },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.search_enable_advance),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.advanceEnabled,
                    onCheckedChange = { state.advanceEnabled = it },
                )
            }
            if (state.advanceEnabled) {
                Spacer(Modifier.height(8.dp))
                AdvanceSearchSection(state)
            }
        } else {
            SectionTitle(R.string.search_image)
            Spacer(Modifier.height(8.dp))
            val uri = state.imageUri
            if (uri == null) {
                Text(
                    text = stringResource(R.string.select_image_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickImage) {
                    Text(stringResource(R.string.select_image))
                }
                if (uri != null) {
                    OutlinedButton(onClick = onClearImage) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showSearchModeTip) {
        AlertDialog(
            onDismissRequest = { showSearchModeTip = false },
            text = { Text(stringResource(R.string.search_tip)) },
            confirmButton = {
                TextButton(onClick = { showSearchModeTip = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(
    @StringRes textRes: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun CheckableChip(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                Image(
                    painter = painterResource(R.drawable.v_check_all_dark_x24),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(14.dp),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(160.dp)
            .combinedClickable(onClick = onClick, onLongClick = {}),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvanceSearchSection(state: SearchPanelState) {
    FlowRow(
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CheckableChip(
            label = stringResource(R.string.search_sh),
            checked = state.hasAdvance(AdvanceSearchTable.SH),
            onClick = { state.toggleAdvance(AdvanceSearchTable.SH) },
            onLongClick = {},
        )
        CheckableChip(
            label = stringResource(R.string.search_sto),
            checked = state.hasAdvance(AdvanceSearchTable.STO),
            onClick = { state.toggleAdvance(AdvanceSearchTable.STO) },
            onLongClick = {},
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.search_sr),
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckableChip(
            label = stringResource(R.string.search_min_rating_none),
            checked = state.minRating !in 2..5,
            onClick = { state.minRating = -1 },
            onLongClick = {},
        )
        STAR_RATINGS.forEach { (labelRes, rating) ->
            CheckableChip(
                label = stringResource(labelRes),
                checked = state.minRating == rating,
                onClick = { state.minRating = rating },
                onLongClick = {},
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.search_sp),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.pageFrom,
            onValueChange = { state.pageFrom = it.filter(Char::isDigit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = stringResource(R.string.search_sp_to),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        OutlinedTextField(
            value = state.pageTo,
            onValueChange = { state.pageTo = it.filter(Char::isDigit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = stringResource(R.string.search_sp_suffix),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.search_sf),
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckableChip(
            label = stringResource(R.string.search_sfl),
            checked = state.hasAdvance(AdvanceSearchTable.SFL),
            onClick = { state.toggleAdvance(AdvanceSearchTable.SFL) },
            onLongClick = {},
        )
        CheckableChip(
            label = stringResource(R.string.search_sfu),
            checked = state.hasAdvance(AdvanceSearchTable.SFU),
            onClick = { state.toggleAdvance(AdvanceSearchTable.SFU) },
            onLongClick = {},
        )
        CheckableChip(
            label = stringResource(R.string.search_sft),
            checked = state.hasAdvance(AdvanceSearchTable.SFT),
            onClick = { state.toggleAdvance(AdvanceSearchTable.SFT) },
            onLongClick = {},
        )
    }
    Box(Modifier.height(8.dp))
}

private val SEARCH_MODES = listOf(
    R.string.search_normal_search to ListUrlBuilder.MODE_NORMAL,
    R.string.search_subscription_search to ListUrlBuilder.MODE_SUBSCRIPTION,
    R.string.search_specify_uploader to ListUrlBuilder.MODE_UPLOADER,
    R.string.search_specify_tag to ListUrlBuilder.MODE_TAG,
)

private val STAR_RATINGS = listOf(
    R.string.star_2 to 2,
    R.string.star_3 to 3,
    R.string.star_4 to 4,
    R.string.star_5 to 5,
)
