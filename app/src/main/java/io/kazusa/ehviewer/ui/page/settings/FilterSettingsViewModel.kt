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
package io.kazusa.ehviewer.ui.page.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.client.EhFilter
import io.kazusa.ehviewer.dao.Filter
import io.kazusa.ehviewer.ui.compose.screen.settings.FilterEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 过滤器页（原 `FilterFragment` 里的 `FilterList` + `AddFilterDialogHelper`）。
 *
 * 数据源是 [EhFilter] 的内存分组（进程启动时从库里读一次），所以「刷新列表」不读库；
 * 增 / 删 / 启停都要写库，一律走 IO。每次写完重建整份快照——老实现是靠
 * `notifyDataSetChanged()`，两者对用户看到的结果一样。
 *
 * [io] 把本页的所有库操作串起来：`EhFilter` 的 `@Synchronized` 只保护单个调用，
 * 读快照时若与写入交错，`ArrayList` 会读到半截。
 */
class FilterSettingsViewModel : ViewModel() {
    var entries by mutableStateOf<List<FilterEntry>>(emptyList())
        private set

    private val io = Mutex()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            entries = withContext(Dispatchers.IO) { io.withLock { snapshot() } }
        }
    }

    /** 添加：`mode` 就是 `array/filter_entries` 的下标，也是数据库里的 MODE */
    fun add(mode: Int, text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                io.withLock {
                    EhFilter.addFilter(
                        Filter().apply {
                            this.mode = mode
                            this.text = text
                        },
                    )
                }
            }
            if (added) {
                entries = withContext(Dispatchers.IO) { io.withLock { snapshot() } }
            }
            onResult(added)
        }
    }

    fun toggle(id: Long) = mutate(id, EhFilter::triggerFilter)

    fun delete(id: Long) = mutate(id, EhFilter::deleteFilter)

    private fun mutate(id: Long, action: (Filter) -> Unit) {
        viewModelScope.launch {
            entries = withContext(Dispatchers.IO) {
                io.withLock {
                    allFilters().firstOrNull { it.id == id }?.let(action)
                    snapshot()
                }
            }
        }
    }

    /** 按老实现的分组顺序摊平：每种模式「有内容才出标题，标题后跟自己的条目」 */
    private fun snapshot(): List<FilterEntry> {
        val translate = Settings.showTagTranslations
        return buildList {
            addGroup(EhFilter.titleFilterList, EhFilter.MODE_TITLE, R.string.filter_title, translate)
            addGroup(EhFilter.uploaderFilterList, EhFilter.MODE_UPLOADER, R.string.filter_uploader, translate)
            addGroup(EhFilter.tagFilterList, EhFilter.MODE_TAG, R.string.filter_tag, translate)
            addGroup(EhFilter.tagNamespaceFilterList, EhFilter.MODE_TAG_NAMESPACE, R.string.filter_tag_namespace, translate)
            addGroup(EhFilter.commenterFilterList, EhFilter.MODE_COMMENTER, R.string.filter_commenter, translate)
            addGroup(EhFilter.commentFilterList, EhFilter.MODE_COMMENT, R.string.filter_comment, translate)
        }
    }

    private fun MutableList<FilterEntry>.addGroup(
        filters: List<Filter>,
        mode: Int,
        titleRes: Int,
        translate: Boolean,
    ) {
        val items = filters.mapNotNull { filter ->
            val id = filter.id ?: return@mapNotNull null
            val text = filter.text ?: return@mapNotNull null
            FilterEntry.Item(
                id = id,
                text = text,
                // 与老实现同一口径：开关打开时标签显示「原文 (翻译)」
                label = if (translate) EhFilter.applyTranslation(filter) ?: text else text,
                enabled = filter.enable == true,
            )
        }
        if (items.isEmpty()) return
        add(FilterEntry.Header(mode = mode, titleRes = titleRes))
        addAll(items)
    }

    private fun allFilters(): List<Filter> = EhFilter.titleFilterList +
        EhFilter.uploaderFilterList +
        EhFilter.tagFilterList +
        EhFilter.tagNamespaceFilterList +
        EhFilter.commenterFilterList +
        EhFilter.commentFilterList
}
