/*
 * Copyright 2024 Moedog
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
package com.hippo.util

import android.text.Html
import android.text.Spanned

/**
 * HTML → [Spanned]。
 *
 * 只保留纯文本转换：带 `ImageGetter` 的那个重载（把 `<img>` 塞进 `ObservedTextView`）
 * 随着老公告视图一起删了，图片一律走 Coil。
 */
fun loadHtml(source: String): Spanned = Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY)
