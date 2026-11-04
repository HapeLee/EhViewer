package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import eu.kanade.tachiyomi.ui.reader.setting.OrientationType
import eu.kanade.tachiyomi.ui.reader.setting.PreferenceType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

/**
 * 底栏四个按钮：阅读模式、屏幕方向、裁边开关、设置。
 *
 * 前两个是「图标 + 下拉」，图标就是当前选中项自己的图标（[PreferenceType.icon]）。
 */
@Composable
fun BottomReaderBar(onClickSettings: () -> Unit, containerColor: Color) = Surface(
    color = containerColor,
    // 半透明的 surface 匹配不上 contentColorFor()，不显式给的话图标和文字会退回平台默认黑色
    contentColor = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val readingMode by Settings.readingMode.collectAsState { ReadingModeType.fromPreference(it) }
        DropdownIconButton(
            label = stringResource(R.string.viewer),
            menuItems = ReadingModeType.entries,
            selectedItem = readingMode,
            onSelectedItemChange = { Settings.readingMode.value = it.prefValue },
            minMenuWidth = 192.dp,
        )
        val orientationMode by Settings.orientationMode.collectAsState { OrientationType.fromPreference(it) }
        DropdownIconButton(
            label = stringResource(R.string.pref_rotation_type),
            menuItems = OrientationType.entries,
            selectedItem = orientationMode,
            onSelectedItemChange = { Settings.orientationMode.value = it.prefValue },
            minMenuWidth = 192.dp,
        )
        var cropBorder by Settings.cropBorder.asMutableState()
        ActionButton(
            onClick = { cropBorder = !cropBorder },
            icon = if (cropBorder) R.drawable.ic_reader_scale else R.drawable.ic_reader_list,
            contentDescription = stringResource(R.string.pref_crop_borders),
        )
        ActionButton(
            onClick = onClickSettings,
            icon = R.drawable.ic_reader_more,
            contentDescription = stringResource(R.string.action_settings),
        )
    }
}

@Composable
private fun DropdownIconButton(
    label: String,
    menuItems: List<PreferenceType>,
    selectedItem: PreferenceType,
    onSelectedItemChange: (PreferenceType) -> Unit,
    modifier: Modifier = Modifier,
    minMenuWidth: Dp = Dp.Unspecified,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ActionButton(
            onClick = { expanded = true },
            icon = selectedItem.icon,
            contentDescription = label,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = minMenuWidth),
        ) {
            menuItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.stringRes)) },
                    onClick = {
                        expanded = false
                        onSelectedItemChange(item)
                    },
                    leadingIcon = {
                        if (selectedItem == item) {
                            Text(text = "✓", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    onClick: () -> Unit,
    icon: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}
