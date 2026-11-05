/*
 * Copyright 2023 Tarsin Norbin
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
package io.kazusa.ehviewer.support.unifile

import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Use Native IO/NIO directly if possible, unless you need process file content on JVM!
 */
fun UniFile.openInputStream(): FileInputStream = AutoCloseInputStream(openFileDescriptor("r"))

/**
 * Use Native IO/NIO directly if possible, unless you need process file content on JVM!
 */
fun UniFile.openOutputStream(): FileOutputStream = AutoCloseOutputStream(openFileDescriptor("wt"))

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * 文件内容的 SHA-1（小写 hex）。
 *
 * 原实现走 JNI（`hash.c` + libnettle），这里换成 JDK 的 [MessageDigest]，
 * 语义保持一致：从文件起始位置读到底，失败返回 null。
 */
fun UniFile.sha1(): String? = runCatching {
    openInputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        val bytes = digest.digest()
        buildString(bytes.size * 2) {
            bytes.forEach {
                append(HEX_DIGITS[(it.toInt() shr 4) and 0xF])
                append(HEX_DIGITS[it.toInt() and 0xF])
            }
        }
    }
}.getOrNull()
