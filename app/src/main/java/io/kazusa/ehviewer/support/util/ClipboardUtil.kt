/*
 * Copyright 2022 Tarsin Norbin
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
package io.kazusa.ehviewer.support.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.TextUtils
import android.view.textclassifier.TextClassifier
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.ui.MainActivity
import io.kazusa.ehviewer.ui.host.TIP_LENGTH_SHORT

fun Context.getClipboardManager(): ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

fun Context.addTextToClipboard(text: CharSequence?, isSensitive: Boolean) {
    getClipboardManager().apply {
        setPrimaryClip(
            ClipData.newPlainText(null, text).apply {
                if (isAtLeastT && isSensitive) {
                    description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            },
        )
    }
    if (this is MainActivity) {
        showTip(R.string.copied_to_clipboard, TIP_LENGTH_SHORT)
    }
}

fun ClipboardManager.getTextFromClipboard(context: Context): String? {
    val item = primaryClip?.getItemAt(0)
    val string = item?.coerceToText(context).toString()
    return if (!TextUtils.isEmpty(string)) string else null
}

fun ClipboardManager.getUrlFromClipboard(context: Context): String? {
    if (primaryClipDescription?.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE) {
        if ((
                primaryClipDescription?.getConfidenceScore(TextClassifier.TYPE_URL)
                    ?.let { it <= 0 }
                ) == true
        ) {
            return null
        }
    }
    val item = primaryClip?.getItemAt(0)
    val string = item?.coerceToText(context).toString()
    return if (!TextUtils.isEmpty(string)) string else null
}
