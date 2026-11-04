package com.hippo.ehviewer.ui.reader

/** 长按页面菜单里的动作；实际执行在 `ReaderRoute`（要碰 ActivityResult / MediaStore） */
enum class ReaderPageAction {
    Refresh,
    Share,
    Copy,
    Save,
    SaveTo,
    DownloadOriginal,
}
