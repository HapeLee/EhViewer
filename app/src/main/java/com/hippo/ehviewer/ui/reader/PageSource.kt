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
package com.hippo.ehviewer.ui.reader

import com.hippo.ehviewer.archive.ArchiveSession
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读器的页数据来源。
 *
 * 在线画廊、已下载画廊、压缩包画廊最终都收敛成这一层，
 * Reader 只认 [count] / [name] / [page]，不关心数据从哪来。
 */
interface PageSource : Closeable {
    val count: Int

    fun name(index: Int): String

    /** 取第 [index] 页落成本地文件（可在同一会话内复用缓存） */
    suspend fun page(index: Int): File
}

/**
 * 压缩包页源：把 [ArchiveSession] 的条目按需解到缓存目录。
 *
 * 之所以落文件而不是直接给 Coil 喂流：Coil 对 File 的缓存与 telephoto 的
 * 区域解码（需要可随机访问的源）都最稳；等确认 telephoto 能直吃流再优化。
 */
class ArchivePageSource(
    private val session: ArchiveSession,
    private val cacheDir: File,
) : PageSource {
    private val entries = session.entries.filterNot { it.isDirectory }

    override val count: Int
        get() = entries.size

    override fun name(index: Int): String = entries[index].name

    override suspend fun page(index: Int): File = withContext(Dispatchers.IO) {
        val entry = entries[index]
        val target = File(cacheDir, "%05d-%s".format(index, entry.name.substringAfterLast('/')))
        if (target.isFile && target.length() > 0) return@withContext target
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "${target.name}.part")
        session.openEntry(index).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("Cannot materialize page $index")
        }
        target
    }

    override fun close() {
        session.close()
        cacheDir.deleteRecursively()
    }
}
