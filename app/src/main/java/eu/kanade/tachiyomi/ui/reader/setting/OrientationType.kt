package eu.kanade.tachiyomi.ui.reader.setting

import android.content.pm.ActivityInfo
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.hippo.ehviewer.R

enum class OrientationType(
    override val prefValue: Int,
    val flag: Int,
    @get:StringRes override val stringRes: Int,
    @get:DrawableRes override val icon: Int,
) : PreferenceType {
    DEFAULT(0, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, R.string.label_default, R.drawable.ic_reader_scale),
    FREE(1, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, R.string.rotation_free, R.drawable.ic_reader_scale),
    PORTRAIT(2, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, R.string.rotation_portrait, R.drawable.ic_reader_list),
    LANDSCAPE(3, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, R.string.rotation_landscape, R.drawable.ic_reader_forward),
    LOCKED_PORTRAIT(4, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, R.string.rotation_force_portrait, R.drawable.ic_reader_list),
    LOCKED_LANDSCAPE(5, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, R.string.rotation_force_landscape, R.drawable.ic_reader_forward),
    REVERSE_PORTRAIT(6, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, R.string.rotation_reverse_portrait, R.drawable.ic_reader_back),
    ;

    companion object {
        fun fromPreference(preference: Int): OrientationType = entries.find { it.prefValue == preference } ?: DEFAULT
    }
}
