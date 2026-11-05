package io.kazusa.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.Settings
import io.kazusa.ehviewer.asMutableState

@Composable
fun ColorFilterSetting() = Column(
    modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
) {
    val customBrightness = Settings.customBrightness.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_custom_brightness),
        field = customBrightness,
    )
    AnimatedVisibility(visible = customBrightness.value) {
        val brightness = Settings.customBrightnessValue.asMutableState()
        SliderChoice(
            startSlot = { Text(text = "☀") },
            endSlot = { Text(text = "${brightness.value}") },
            range = -75..100,
            field = brightness,
        )
    }
    val colorFilter = Settings.colorFilter.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_custom_color_filter),
        field = colorFilter,
    )
    AnimatedVisibility(visible = colorFilter.value) {
        var color by Settings.colorFilterValue.asMutableState()
        val rf = remember { mutableIntStateOf(color shr 16 and 0xFF) }
        val r by rf
        val gf = remember { mutableIntStateOf(color shr 8 and 0xFF) }
        val g by gf
        val bf = remember { mutableIntStateOf(color and 0xFF) }
        val b by bf
        val af = remember { mutableIntStateOf(color ushr 24 and 0xFF) }
        val a by af
        color = (a shl 24) or (r shl 16) or (g shl 8) or b
        Column {
            SliderChoice(startSlot = { Text(text = "R") }, endSlot = { Text(text = "$r") }, range = 0..255, field = rf)
            SliderChoice(startSlot = { Text(text = "G") }, endSlot = { Text(text = "$g") }, range = 0..255, field = gf)
            SliderChoice(startSlot = { Text(text = "B") }, endSlot = { Text(text = "$b") }, range = 0..255, field = bf)
            SliderChoice(startSlot = { Text(text = "A") }, endSlot = { Text(text = "$a") }, range = 0..255, field = af)
        }
    }
    SpinnerChoice(
        title = stringResource(id = R.string.pref_color_filter_mode),
        entries = stringArrayResource(id = R.array.color_filter_modes),
        values = listOf(0, 1, 2, 3, 4, 5),
        field = Settings.colorFilterMode.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_grayscale),
        field = Settings.grayScale.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_inverted_colors),
        field = Settings.invertedColors.asMutableState(),
    )
}
