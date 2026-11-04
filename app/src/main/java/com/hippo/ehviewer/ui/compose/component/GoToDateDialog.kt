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
 * EhViewer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with EhViewer.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.ui.compose.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.util.toLocalDateTime

/**
 * 「跳转到指定日期」的选择器（Compose 版）。
 *
 * 原来用的是 MDC 的 `MaterialDatePicker`：它是 View 体系的 DialogFragment，颜色只能
 * 吃 XML 主题里的静态值（既不跟 Monet，也不跟 AMOLED 的纯黑），还得靠宿主的
 * `supportFragmentManager` 弹出来。换成 M3 的 [DatePickerDialog] 之后，配色直接来自
 * `MaterialTheme`，和页面是同一套色板。
 *
 * @param range 可选区间（UTC 毫秒，首尾都含）
 * @param initialDateMillis 打开时选中的日期
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoToDateDialog(
    range: LongRange,
    initialDateMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (millis: Long) -> Unit,
) {
    val startYear = range.first.toLocalDateTime().year
    val endYear = range.last.toLocalDateTime().year
    val selectableDates = remember(range) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in range

            // 年份选择器只按「这一年里有没有可选日期」判断，逐日校验交给上面那个
            override fun isSelectableYear(year: Int): Boolean = year in startYear..endYear
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDateMillis,
        yearRange = startYear..endYear,
        selectableDates = selectableDates,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let(onConfirm) },
                enabled = state.selectedDateMillis != null,
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    text = stringResource(R.string.go_to),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 12.dp),
                )
            },
        )
    }
}
