package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState

@Composable
fun ReaderGeneralSetting() = Column(
    modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
) {
    SpinnerChoice(
        title = stringResource(id = R.string.pref_reader_theme),
        entries = stringArrayResource(id = R.array.reader_themes),
        values = integerArrayResource(id = R.array.reader_themes_values).toList(),
        field = Settings.readerTheme.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_show_page_number),
        field = Settings.showPageNumber.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_show_reader_seekbar),
        field = Settings.showReaderSeekbar.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_double_tap_to_zoom),
        field = Settings.doubleTapToZoom.asMutableState(),
    )
    val fullscreen = Settings.fullscreen.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_fullscreen),
        field = fullscreen,
    )
    val view = LocalView.current
    // minSdk 31，displayCutout 一定有 API；这里只判断设备有没有挖孔
    val hasDisplayCutout = remember(view) { view.rootWindowInsets?.displayCutout != null }
    if (hasDisplayCutout) {
        AnimatedVisibility(visible = fullscreen.value) {
            SwitchChoice(
                title = stringResource(id = R.string.pref_cutout_short),
                field = Settings.cutoutShort.asMutableState(),
            )
        }
    }
    SwitchChoice(
        title = stringResource(id = R.string.pref_keep_screen_on),
        field = Settings.keepScreenOnPref.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_read_with_long_tap),
        field = Settings.readerLongTapAction.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_page_transitions),
        field = Settings.pageTransitions.asMutableState(),
    )
    val volume = Settings.readWithVolumeKeys.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.settings_read_volume_page),
        field = volume,
    )
    AnimatedVisibility(visible = volume.value) {
        SwitchChoice(
            title = stringResource(id = R.string.settings_read_reverse_volume),
            field = Settings.readWithVolumeKeysInverted.asMutableState(),
        )
    }
    // 预取邻居页数：1..10，默认 5（与 Settings.readerPreloadImage 的默认值一致）
    SpinnerChoice(
        title = stringResource(id = R.string.settings_download_preload_image),
        entries = (1..10).map { "$it" }.toTypedArray(),
        values = (1..10).toList(),
        field = Settings.readerPreloadImage.asMutableState(),
    )
}
