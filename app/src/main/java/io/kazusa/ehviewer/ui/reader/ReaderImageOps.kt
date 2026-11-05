/*
 * Copyright 2026 EhViewer contributors
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
package io.kazusa.ehviewer.ui.reader

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import io.kazusa.ehviewer.AppConfig
import io.kazusa.ehviewer.BuildConfig
import io.kazusa.ehviewer.R
import io.kazusa.ehviewer.client.EhUrl
import io.kazusa.ehviewer.support.util.ExceptionUtils
import io.kazusa.ehviewer.support.util.getClipboardManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 阅读器的图片操作，全部从旧 GalleryActivity 搬过来，顺序与分支保持一致。
//
// 只依赖 Context，不依赖 Activity——ActivityResultContracts（另存为）由
// ReaderRoute 用 rememberLauncherForActivityResult 补上，这一层只负责写字节。

/** 分享用的临时目录：`externalCacheDir/temp` */
fun readerTempDir(): File? = AppConfig.getExternalTempDir()

/** 复制用的临时目录：`externalCacheDir/copy`，与分享分开免得互相覆盖 */
fun readerCopyTempDir(): File? = AppConfig.getExternalCopyTempDir()

/** 画廊详情页链接，分享时顺带带出去（旧实现同款） */
fun galleryUrlOrNull(gid: Long, token: String?): String? = if (token.isNullOrEmpty()) null else EhUrl.getGalleryDetailUrl(gid, token)

/** 拷一份到临时目录：不能直接把阅读器的会话缓存目录暴露给别的 App */
suspend fun copyToTemp(source: File, dir: File?, filename: String): File? = withContext(Dispatchers.IO) {
    val target = File(dir ?: return@withContext null, filename)
    runCatching {
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target
    }.getOrNull()
}

private fun mimeTypeOf(filename: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(filename))
    ?: "image/jpeg"

private fun Context.providerUri(file: File): Uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)

private fun Context.toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

/** 分享：FileProvider URI + `ACTION_SEND`，顺带把画廊链接塞进 `EXTRA_TEXT` */
fun Context.shareImage(file: File, galleryUrl: String?) {
    val uri = providerUri(file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_STREAM, uri)
        if (galleryUrl != null) putExtra(Intent.EXTRA_TEXT, galleryUrl)
        setDataAndType(uri, mimeTypeOf(file.name))
    }
    runCatching {
        startActivity(
            Intent.createChooser(intent, getString(R.string.share_image)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        ExceptionUtils.throwIfFatal(it)
        toast(R.string.error_cant_find_activity)
    }
}

/** 复制：把图片 URI 写进剪贴板（`ClipData.newUri`，纯文本工具用不上） */
fun Context.copyImage(file: File) {
    val uri = providerUri(file)
    getClipboardManager().setPrimaryClip(ClipData.newUri(contentResolver, "ehviewer", uri))
    toast(R.string.copied_to_clipboard)
}

/**
 * 保存到相册：`MediaStore` 插一条 pending 记录再写字节。
 *
 * minSdk 31 已经远超 Q，所以不需要 `WRITE_EXTERNAL_STORAGE`——旧实现里那一段
 * 权限请求是为了 Q 以下，这里直接删掉。
 */
suspend fun Context.saveImageToAlbum(file: File, filename: String): Boolean = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(filename))
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${AppConfig.APP_DIRNAME}")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        withContext(Dispatchers.Main) { toast(R.string.error_cant_save_image) }
        return@withContext false
    }
    val ok = runCatching {
        contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
    }.isSuccess
    if (!ok) {
        contentResolver.delete(uri, null, null)
        withContext(Dispatchers.Main) { toast(R.string.error_cant_save_image) }
        return@withContext false
    }
    val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    contentResolver.update(uri, published, null, null)
    withContext(Dispatchers.Main) {
        Toast.makeText(
            this@saveImageToAlbum,
            getString(
                R.string.image_saved,
                "${Environment.DIRECTORY_PICTURES}/${AppConfig.APP_DIRNAME}/$filename",
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }
    true
}

/** 另存为：把 [source] 写进 SAF 返回的 [uri]；[source] 用完就删 */
suspend fun Context.writeToUri(source: File, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    val ok = runCatching {
        contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
    }.isSuccess
    source.delete()
    if (ok) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@writeToUri, getString(R.string.image_saved, uri.path), Toast.LENGTH_SHORT).show()
        }
    }
    ok
}
