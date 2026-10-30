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

/** 靠魔数识别，不信任扩展名 */
enum class ArchiveFormat {
    ZIP,
    SEVEN_Z,
    TAR,
    RAR,
    GZIP,
    XZ,
    BZIP2,
    UNKNOWN,
}

object ArchiveFormats {
    const val SNIFF_SIZE = 512

    fun sniff(header: ByteArray): ArchiveFormat {
        fun at(offset: Int, vararg bytes: Int): Boolean = header.size >= offset + bytes.size && bytes.indices.all { header[offset + it] == bytes[it].toByte() }

        return when {
            // PK\x03\x04 / 空包 PK\x05\x06 / 分卷 PK\x07\x08
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) || at(0, 0x50, 0x4B, 0x07, 0x08) ->
                ArchiveFormat.ZIP
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveFormat.SEVEN_Z
            // RAR4 与 RAR5 都从这里开始
            at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> ArchiveFormat.RAR
            at(0, 0x1F, 0x8B) -> ArchiveFormat.GZIP
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> ArchiveFormat.XZ
            at(0, 0x42, 0x5A, 0x68) -> ArchiveFormat.BZIP2
            header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar" -> ArchiveFormat.TAR
            else -> ArchiveFormat.UNKNOWN
        }
    }
}
