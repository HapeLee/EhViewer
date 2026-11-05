package io.kazusa.ehviewer.tachiyomi.ui.reader.setting

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import io.kazusa.ehviewer.R

@Stable
enum class ReadingModeType(
    override val prefValue: Int,
    @get:StringRes override val stringRes: Int,
    @get:DrawableRes override val icon: Int,
) : PreferenceType {
    DEFAULT(0, R.string.label_default, R.drawable.ic_reader_scale),
    LEFT_TO_RIGHT(1, R.string.left_to_right_viewer, R.drawable.ic_reader_forward),
    RIGHT_TO_LEFT(2, R.string.right_to_left_viewer, R.drawable.ic_reader_back),
    VERTICAL(3, R.string.vertical_viewer, R.drawable.ic_reader_list),
    WEBTOON(4, R.string.webtoon_viewer, R.drawable.ic_reader_list),
    CONTINUOUS_VERTICAL(5, R.string.vertical_plus_viewer, R.drawable.ic_reader_auto_page),
    ;

    companion object {
        @Stable
        fun isWebtoon(type: ReadingModeType) = when (type) {
            DEFAULT, LEFT_TO_RIGHT, RIGHT_TO_LEFT, VERTICAL -> false
            WEBTOON, CONTINUOUS_VERTICAL -> true
        }

        fun isVertical(type: ReadingModeType) = when (type) {
            DEFAULT, LEFT_TO_RIGHT, RIGHT_TO_LEFT -> false
            VERTICAL, WEBTOON, CONTINUOUS_VERTICAL -> true
        }

        fun fromPreference(preference: Int): ReadingModeType = entries.find { it.prefValue == preference } ?: DEFAULT
    }
}
