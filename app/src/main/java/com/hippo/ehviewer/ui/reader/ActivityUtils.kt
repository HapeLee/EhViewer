package com.hippo.ehviewer.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** 从任意 Context 往上找到宿主 Activity（阅读器要改方向、亮度、系统栏） */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
