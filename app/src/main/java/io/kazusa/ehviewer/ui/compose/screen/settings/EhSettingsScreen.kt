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
package io.kazusa.ehviewer.ui.compose.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings

/**
 * Eh 分类页（原 `eh_settings.xml` + `EhFragment`）。
 *
 * 与老实现逐项对齐，包括三个「按条件隐藏」的规则：
 * - 未登录时隐藏 `Settings.SIGN_IN_REQUIRED` 里的 7 项（账号行例外，它要提示「未登录」）；
 * - 当前语言没有标签翻译包时，`show_tag_translations` / `tag_translations_source` 不显示；
 * - `list_mode` 决定尺寸项：详情模式调「详情尺寸 + 列表缩略图宽度」，
 *   其它模式调「缩略图尺寸 + 是否显示标题」（老实现是同一套 `isVisible` 切换）。
 *
 * 账号与图片限额两项有自己的网络流程，交给 [onOpenAccount] / [onOpenImageLimits]
 * 打开对话框，页面本身不碰 IO。
 *
 * 排版按「账号与站点 / 外观与列表 / 详情页 / 翻译与内容」四张分组卡摆放。
 */
@Composable
fun EhSettingsScreen(
    signedIn: Boolean,
    displayName: String?,
    tagTranslatable: Boolean,
    imageLimitsSummary: String,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImageLimits: () -> Unit,
    onOpenUConfig: () -> Unit,
    onOpenMyTags: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onThemeChanged: (Int) -> Unit = {},
    onBlackDarkThemeChanged: (Boolean) -> Unit = {},
    onGallerySiteChanged: (Int) -> Unit = {},
    onShowTagTranslationsChanged: (Boolean) -> Unit = {},
) {
    val listMode = Settings.valueFlowIntFromStr(Settings.KEY_LIST_MODE, 0)
        .collectAsState(initial = Settings.listMode).value
    val translationsSourceUrl = stringResource(R.string.settings_eh_tag_translations_source_url)

    SettingsScaffold(titleRes = R.string.settings_eh, onBack = onBack) {
        SettingsGroup {
            PreferenceRow(
                title = stringResource(R.string.settings_eh_account_name),
                summary = displayName ?: stringResource(R.string.settings_eh_account_name_tourist),
                onClick = onOpenAccount,
            )
            if (signedIn) {
                ListPreferenceRow(
                    key = Settings.KEY_GALLERY_SITE,
                    default = 0,
                    titleRes = R.string.settings_eh_gallery_site,
                    entriesRes = R.array.gallery_site_entries,
                    valuesRes = R.array.gallery_site_entry_values,
                    onChange = onGallerySiteChanged,
                )
                PreferenceRow(
                    title = stringResource(R.string.settings_eh_image_limits),
                    summary = imageLimitsSummary,
                    onClick = onOpenImageLimits,
                )
                PreferenceRow(
                    title = stringResource(R.string.settings_eh_u_config),
                    summary = stringResource(R.string.settings_eh_u_config_summary),
                    onClick = onOpenUConfig,
                )
                PreferenceRow(
                    title = stringResource(R.string.settings_eh_my_tags),
                    summary = stringResource(R.string.settings_eh_my_tags_summary),
                    onClick = onOpenMyTags,
                )
            }
        }

        SettingsGroup {
            ListPreferenceRow(
                key = Settings.KEY_THEME,
                default = -1,
                titleRes = R.string.dark_theme,
                entriesRes = R.array.night_mode_entries,
                valuesRes = R.array.night_mode_values,
                onChange = onThemeChanged,
            )
            SwitchPreferenceRow(
                key = Settings.KEY_BLACK_DARK_THEME,
                default = false,
                titleRes = R.string.settings_eh_black_dark_theme,
                onChange = onBlackDarkThemeChanged,
            )
            ListPreferenceRow(
                key = Settings.KEY_LAUNCH_PAGE,
                default = 0,
                titleRes = R.string.settings_eh_launch_page,
                entriesRes = R.array.launch_page_entries,
                valuesRes = R.array.launch_page_entry_values,
            )
            ListPreferenceRow(
                key = Settings.KEY_LIST_MODE,
                default = 0,
                titleRes = R.string.settings_eh_list_mode,
                entriesRes = R.array.list_mode_entries,
                valuesRes = R.array.list_mode_entry_values,
            )

            if (listMode == 0) {
                SliderPreferenceRow(
                    key = Settings.KEY_DETAIL_SIZE,
                    default = 8,
                    titleRes = R.string.settings_eh_detail_size,
                    range = 6..12,
                )
                SliderPreferenceRow(
                    key = Settings.KEY_LIST_THUMB_SIZE,
                    default = 40,
                    titleRes = R.string.settings_eh_list_tile_thumb_size,
                    range = 35..60,
                )
            } else {
                SliderPreferenceRow(
                    key = Settings.KEY_THUMB_SIZE,
                    default = 4,
                    titleRes = R.string.settings_eh_thumb_size,
                    range = 2..10,
                )
                SwitchPreferenceRow(
                    key = Settings.KEY_THUMB_SHOW_TITLE,
                    default = true,
                    titleRes = R.string.settings_eh_thumb_show_title,
                )
            }
        }

        SettingsGroup {
            if (signedIn) {
                SwitchPreferenceRow(
                    key = Settings.KEY_SHOW_JPN_TITLE,
                    default = false,
                    titleRes = R.string.settings_eh_show_jpn_title,
                    summaryRes = R.string.settings_eh_show_jpn_title_summary,
                )
            }
            SwitchPreferenceRow(
                key = Settings.KEY_SHOW_GALLERY_PAGES,
                default = true,
                titleRes = R.string.settings_eh_show_gallery_pages,
                summaryRes = R.string.settings_eh_show_gallery_pages_summary,
            )
            SwitchPreferenceRow(
                key = Settings.KEY_SHOW_COMMENTS,
                default = true,
                titleRes = R.string.settings_eh_show_gallery_comments,
                summaryRes = R.string.settings_eh_show_gallery_comments_summary,
            )
            SliderPreferenceRow(
                key = Settings.KEY_COMMENT_THRESHOLD,
                default = -101,
                titleRes = R.string.settings_eh_comment_threshold,
                summaryRes = R.string.settings_eh_comment_threshold_summary,
                range = -101..100,
            )
            SliderPreferenceRow(
                key = Settings.KEY_PREVIEW_NUM,
                default = 60,
                titleRes = R.string.settings_eh_preview_num,
                range = 0..200,
            )
            SliderPreferenceRow(
                key = Settings.KEY_PREVIEW_SIZE,
                default = 3,
                titleRes = R.string.settings_eh_preview_size,
                range = 1..10,
            )
        }

        SettingsGroup {
            if (tagTranslatable) {
                SwitchPreferenceRow(
                    key = Settings.KEY_SHOW_TAG_TRANSLATIONS,
                    default = false,
                    titleRes = R.string.settings_eh_show_tag_translations,
                    summaryRes = R.string.settings_eh_show_tag_translations_summary,
                    onChange = onShowTagTranslationsChanged,
                )
                PreferenceRow(
                    title = stringResource(R.string.settings_eh_tag_translations_source),
                    summary = translationsSourceUrl,
                    onClick = { onOpenUrl(translationsSourceUrl) },
                )
            }
            PreferenceRow(
                title = stringResource(R.string.settings_eh_filter),
                summary = stringResource(R.string.settings_eh_filter_summary),
                onClick = onOpenFilter,
            )
            SwitchPreferenceRow(
                key = Settings.KEY_METERED_NETWORK_WARNING,
                default = false,
                titleRes = R.string.settings_eh_metered_network_warning,
            )
            if (signedIn) {
                SwitchPreferenceRow(
                    key = Settings.KEY_REQUEST_NEWS,
                    default = false,
                    titleRes = R.string.settings_eh_request_news,
                )
            }
            SwitchPreferenceRow(
                key = Settings.KEY_HIDE_HV_EVENTS,
                default = false,
                titleRes = R.string.settings_eh_hide_hv_events,
            )
        }
    }
}
