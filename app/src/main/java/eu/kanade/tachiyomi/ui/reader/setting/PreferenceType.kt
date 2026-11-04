package eu.kanade.tachiyomi.ui.reader.setting

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * 下拉按钮的通用契约：值、显示名、图标。
 *
 * EHz 用 `ImageVector`（自带图标集），这里换成 drawable res，直接用 app 里现成的
 * `ic_reader_*`，省掉一整套矢量代码。
 */
interface PreferenceType {
    val prefValue: Int

    @get:StringRes
    val stringRes: Int

    @get:DrawableRes
    val icon: Int
}
