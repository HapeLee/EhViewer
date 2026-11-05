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
package io.kazusa.ehviewer.client.data

/**
 * 进阶搜索的位标志，对应 `ListUrlBuilder.advanceSearch`。
 *
 * 这些常量原来挂在 `widget/AdvanceSearchTable`（一个 `LinearLayout`）的 companion 上，
 * 而那个 View 只有老的 XML 搜索面板在用；面板换成 Compose 的 `SearchPanel` 之后，
 * 剩下的消费者只有 [ListUrlBuilder] 与 `SearchPanelState`，所以常量搬到这里，
 * View 与 `widget_advance_search_table.xml` 一起退役。
 *
 * 位值不要改：与 `f_sh` / `f_sto` / `f_sfl` / `f_sfu` / `f_sft` 的查询参数一一对应，
 * 老版本存进历史记录里的 `advanceSearch` 也是这套位。
 */
object AdvanceSearchFlags {
    /** `f_sh`：只显示已被删除（expunged）的画廊 */
    const val SH = 0x1

    /** `f_sto`：只显示带种子的画廊 */
    const val STO = 0x2

    /** `f_sfl`：关闭「语言」默认过滤 */
    const val SFL = 0x100

    /** `f_sfu`：关闭「上传者」默认过滤 */
    const val SFU = 0x200

    /** `f_sft`：关闭「标签」默认过滤 */
    const val SFT = 0x400
}
