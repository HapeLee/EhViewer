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
package com.hippo.ehviewer.ui.compose.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R

// 与原 about_settings.xml 的 app:url 一致（同样是「点开浏览器」而不是应用内页面）
private const val URL_ISSUES = "https://github.com/EhViewer-NekoInverter/EhViewer/issues/18"
private const val URL_LATEST_RELEASE = "https://github.com/EhViewer-NekoInverter/EhViewer/releases"
private const val URL_SOURCE = "https://github.com/EhViewer-NekoInverter/EhViewer"

/**
 * 关于页（原 `about_settings.xml` + `AboutFragment`）。
 *
 * 纯展示、无开关，所以是最安全的第一页：`Preference` 的「标题 + 摘要」两行结构直接
 * 换成 Compose 的两行文本，`UrlPreference` 的点击打开浏览器下沉成 [onOpenUrl]。
 *
 * 作者那一段原本是 HTML（`<del>` 标已卸任的作者），这里仍交给
 * [AnnotatedString.fromHtml] 解析，而不是先剥标签再显示纯文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    // 原实现把 `$` 换成 `@` 之后交给 Html.fromHtml，这里保持同一条链路
    val authorHtml = stringResource(R.string.settings_about_author_summary).replace('$', '@')
    val authorSummary = remember(authorHtml) { AnnotatedString.fromHtml(authorHtml) }
    val versionSummary = "${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA})\n" +
        stringResource(R.string.settings_about_build_time, BuildConfig.BUILD_TIME)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.v_arrow_left_dark_x24),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            AboutRow(
                title = stringResource(R.string.settings_about_declaration),
                summary = AnnotatedString(stringResource(R.string.settings_about_declaration_summary)),
            )
            AboutRow(
                title = stringResource(R.string.settings_about_author),
                summary = authorSummary,
            )
            AboutRow(
                title = stringResource(R.string.settings_about_issues),
                summary = AnnotatedString(URL_ISSUES),
                onOpenUrl = onOpenUrl,
            )
            AboutRow(
                title = stringResource(R.string.settings_about_latest_release),
                summary = AnnotatedString(URL_LATEST_RELEASE),
                onOpenUrl = onOpenUrl,
            )
            AboutRow(
                title = stringResource(R.string.settings_about_source),
                summary = AnnotatedString(URL_SOURCE),
                onOpenUrl = onOpenUrl,
            )
            AboutRow(
                title = stringResource(R.string.settings_about_version),
                summary = AnnotatedString(versionSummary),
                showDivider = false,
            )
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    summary: AnnotatedString,
    onOpenUrl: ((String) -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val modifier = if (onOpenUrl != null) {
        Modifier.clickable { onOpenUrl(summary.text) }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
