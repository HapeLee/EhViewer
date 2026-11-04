package eu.kanade.tachiyomi.ui.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.compose.component.DialogListItemSpacing
import com.hippo.ehviewer.ui.compose.component.SheetListItem

/**
 * 长按页面的动作面板。
 *
 * 与 EHz 的条目一致（重试 / 看原图 / 分享 / 复制 / 保存 / 另存为）；真正的动作由
 * 宿主 [com.hippo.ehviewer.ui.compose.reader.ReaderRoute] 执行，这一层只管菜单。
 *
 * 行样式与对话框里的列表项统一：圆角块 + 4dp 间距（[SheetListItem] 用 sheet 档的底色）。
 */
@Composable
fun ReaderPageSheetMeta(
    retry: () -> Unit,
    retryOrigin: () -> Unit,
    share: () -> Unit,
    copy: () -> Unit,
    save: () -> Unit,
    saveTo: () -> Unit,
    dismiss: () -> Unit,
) {
    @Composable
    fun Item(@StringRes text: Int, onClick: () -> Unit) = SheetListItem(
        text = stringResource(id = text),
        onClick = {
            onClick()
            dismiss()
        },
    )
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(DialogListItemSpacing),
    ) {
        Item(text = R.string.refresh, onClick = retry)
        Item(text = R.string.view_original, onClick = retryOrigin)
        Item(text = R.string.action_share, onClick = share)
        Item(text = R.string.action_copy, onClick = copy)
        Item(text = R.string.action_save, onClick = save)
        Item(text = R.string.action_save_to, onClick = saveTo)
    }
}
