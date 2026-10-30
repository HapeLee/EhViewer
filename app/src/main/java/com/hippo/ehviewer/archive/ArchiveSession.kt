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
package com.hippo.ehviewer.archive

import java.io.Closeable
import java.io.InputStream

/** 压缩包里的一个条目（已经扁平化，不含目录层级语义） */
data class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
)

/**
 * 一个压缩包的独立会话。
 *
 * 设计要点（替代原来 C 层的全局 archive 状态）：
 * - 每个压缩包一条会话，密码、条目表、游标都不共享
 * - [openEntry] 由实现内部串行化：7z 固实包与 TAR 都不允许并发读不同条目
 * - 不承诺线程安全，调用方仍需保证 [close] 与 [openEntry] 不交叉
 */
interface ArchiveSession : Closeable {
    val entries: List<ArchiveEntry>

    /** 取第 [index] 个条目的内容流，调用方负责 close */
    fun openEntry(index: Int): InputStream
}

/** 格式本身不支持（RAR、tar.gz 等），属于“能力边界”而不是“文件坏了” */
class UnsupportedArchiveException(message: String) : Exception(message)

/** 需要密码才能继续，调用方拿到密码后重新 [ArchiveOpener.open] */
class ArchivePasswordRequiredException(message: String = "Archive password required") : Exception(message)

/** 文件确实损坏或解析失败 */
class ArchiveBrokenException(message: String, cause: Throwable? = null) : Exception(message, cause)
