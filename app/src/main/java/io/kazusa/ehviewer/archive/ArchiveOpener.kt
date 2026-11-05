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
package io.kazusa.ehviewer.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * 会话的数据来源。
 *
 * 抽象出来是为了两件事：Android 侧用 dup 出来的 fd 不干扰调用方的 pfd；
 * 单元测试侧可以直接喂 [File]，这样 zip/7z/tar 的解析逻辑能在 JVM 上验证。
 */
internal interface ArchiveChannel : Closeable {
    val channel: FileChannel
}

/**
 * 持有从 [ParcelFileDescriptor] dup 出来的独立 fd，保证关闭会话不会影响调用方的 pfd。
 *
 * 注意：关闭顺序是 stream → dup，两次关闭都兜住异常，避免 EBADF 上抛。
 */
internal class PfdArchiveChannel(pfd: ParcelFileDescriptor) : ArchiveChannel {
    private val dup = pfd.dup()
    private val stream = FileInputStream(dup.fileDescriptor)

    override val channel: FileChannel get() = stream.channel

    override fun close() {
        runCatching { stream.close() }
        runCatching { dup.close() }
    }
}

internal class FileArchiveChannel(file: File) : ArchiveChannel {
    private val stream = FileInputStream(file)

    override val channel: FileChannel get() = stream.channel

    override fun close() {
        runCatching { stream.close() }
    }
}

/**
 * 打开一个压缩包：[open] 每次调用都会新建 channel 与会话，密码错误时可以直接用新密码重试。
 *
 * 密码语义：
 * - 明文包直接返回会话
 * - 加密包在 [password] 为空时抛 [ArchivePasswordRequiredException]
 */
class ArchiveOpener(
    private val context: Context,
    private val pfd: ParcelFileDescriptor,
) {
    private val sourceSize: Long = pfd.statSize.coerceAtLeast(0)

    fun open(password: CharArray? = null): ArchiveSession {
        val format = ArchiveFormats.sniff(readHeader())
        return when (format) {
            ArchiveFormat.ZIP -> openZip(password)
            ArchiveFormat.SEVEN_Z -> openSevenZ(password)
            ArchiveFormat.TAR -> TarArchiveSession(PfdArchiveChannel(pfd), sourceSize)
            ArchiveFormat.RAR ->
                throw UnsupportedArchiveException("RAR/CBR is not supported yet, please repack as ZIP/CBZ")
            ArchiveFormat.GZIP, ArchiveFormat.XZ, ArchiveFormat.BZIP2 ->
                throw UnsupportedArchiveException("tar.gz / tar.xz is not supported yet, please extract it first")
            ArchiveFormat.UNKNOWN ->
                throw UnsupportedArchiveException("Unrecognized archive format")
        }
    }

    private fun readHeader(): ByteArray {
        val holder = PfdArchiveChannel(pfd)
        try {
            val buffer = ByteBuffer.allocate(ArchiveFormats.SNIFF_SIZE)
            holder.channel.position(0)
            while (buffer.hasRemaining()) {
                val read = holder.channel.read(buffer)
                if (read <= 0) break
            }
            return buffer.array().copyOf(buffer.position())
        } finally {
            holder.close()
        }
    }

    private fun openZip(password: CharArray?): ArchiveSession {
        val holder = PfdArchiveChannel(pfd)
        val zip = try {
            ZipFile.builder()
                .setSeekableByteChannel(holder.channel)
                .setUseUnicodeExtraFields(true)
                .get()
        } catch (e: IOException) {
            holder.close()
            throw ArchiveBrokenException("Failed to read zip central directory", e)
        }

        val encrypted = runCatching {
            zip.entries.toList().any { it.generalPurposeBit?.usesEncryption() == true }
        }.getOrDefault(false)

        if (!encrypted) {
            return PlainZipSession(zip, holder)
        }

        runCatching { zip.close() }
        holder.close()

        if (password == null) {
            throw ArchivePasswordRequiredException()
        }

        // zip4j 只能吃 File，加密包才付这一次物化成本
        val cache = File(context.cacheDir, "archive-${System.nanoTime()}.zip")
        materialize(cache)
        return try {
            Zip4jSession(cache, password)
        } catch (e: Exception) {
            cache.delete()
            throw ArchivePasswordRequiredException(e.message ?: "Wrong password")
        }
    }

    private fun openSevenZ(password: CharArray?): ArchiveSession {
        val holder = PfdArchiveChannel(pfd)
        return try {
            val sevenZ = if (password == null) {
                SevenZFile(holder.channel)
            } else {
                SevenZFile(holder.channel, password)
            }
            SevenZSession(sevenZ, holder)
        } catch (e: PasswordRequiredException) {
            holder.close()
            throw ArchivePasswordRequiredException()
        } catch (e: IOException) {
            holder.close()
            // commons-compress 在密码错误时也走 IOException，用文案区分
            if (e.message?.contains("assword", ignoreCase = true) == true) {
                throw ArchivePasswordRequiredException(e.message!!)
            }
            throw ArchiveBrokenException("Failed to read 7z header", e)
        }
    }

    private fun materialize(dst: File) {
        val holder = PfdArchiveChannel(pfd)
        try {
            FileOutputStream(dst).use { out ->
                val channel = holder.channel
                channel.position(0)
                val buffer = ByteBuffer.allocateDirect(64 * 1024)
                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read <= 0) break
                    buffer.flip()
                    val chunk = ByteArray(read)
                    buffer.get(chunk)
                    out.write(chunk)
                }
            }
        } finally {
            holder.close()
        }
    }
}

/** 未加密 ZIP：commons-compress 直接读 channel，无需落盘 */
internal class PlainZipSession(
    private val zip: ZipFile,
    private val holder: ArchiveChannel,
) : ArchiveSession {
    private val lock = Any()
    private val zipEntries: List<ZipArchiveEntry> = zip.entries.toList()

    override val entries: List<ArchiveEntry> = zipEntries.map {
        ArchiveEntry(it.name, it.size, it.isDirectory)
    }

    override fun openEntry(index: Int): InputStream = synchronized(lock) {
        zip.getInputStream(zipEntries[index])
    }

    override fun close() {
        runCatching { zip.close() }
        holder.close()
    }
}

/** 加密 ZIP：zip4j 读取物化后的临时文件 */
internal class Zip4jSession(
    private val file: File,
    password: CharArray,
) : ArchiveSession {
    private val zip = net.lingala.zip4j.ZipFile(file, password)
    private val headers = zip.fileHeaders.filterNot { it.isDirectory }

    override val entries: List<ArchiveEntry> = headers.map {
        ArchiveEntry(it.fileName, it.uncompressedSize, it.isDirectory)
    }

    override fun openEntry(index: Int): InputStream = zip.getInputStream(headers[index])

    override fun close() {
        runCatching { zip.close() }
        file.delete()
    }
}

/** 7z：固实包只能顺序读，调用方需串行化（provider 侧已限制并发） */
internal class SevenZSession(
    private val sevenZ: SevenZFile,
    private val holder: ArchiveChannel,
) : ArchiveSession {
    private val lock = Any()
    private val sevenEntries: List<SevenZArchiveEntry> = sevenZ.entries.toList()

    override val entries: List<ArchiveEntry> = sevenEntries.map {
        ArchiveEntry(it.name, it.size, it.isDirectory)
    }

    override fun openEntry(index: Int): InputStream = synchronized(lock) {
        sevenZ.getInputStream(sevenEntries[index])
    }

    override fun close() {
        runCatching { sevenZ.close() }
        holder.close()
    }
}

/**
 * 未压缩 TAR：手工解析 512 字节块头建索引，之后按偏移随机读。
 * 不支持 GNU 长文件名（'L' 块）与 PAX 扩展头，遇到就退回原始短名。
 */
internal class TarArchiveSession(
    private val holder: ArchiveChannel,
    private val sourceSize: Long,
) : ArchiveSession {
    private val dataOffsets = ArrayList<Long>()
    private val dataSizes = ArrayList<Long>()

    override val entries: List<ArchiveEntry> = buildList {
        val header = ByteBuffer.allocate(TAR_BLOCK)
        var pos = 0L
        while (pos + TAR_BLOCK <= sourceSize) {
            header.clear()
            if (!readFully(header, pos)) break
            if (header.array().all { it == 0.toByte() }) break

            var name = header.cString(0, 100)
            val size = header.octal(124, 12)
            val typeFlag = header.get(156).toInt().toChar()
            val prefix = header.cString(345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"

            val dataOffset = pos + TAR_BLOCK
            val isDirectory = typeFlag == '5' || name.endsWith('/')
            if (!isDirectory && size > 0) {
                add(ArchiveEntry(name, size, false))
                dataOffsets.add(dataOffset)
                dataSizes.add(size)
            }
            pos = dataOffset + ((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK
        }
    }

    override fun openEntry(index: Int): InputStream {
        val size = dataSizes[index].toInt()
        val buffer = ByteBuffer.allocate(size)
        if (!readFully(buffer, dataOffsets[index])) throw ArchiveBrokenException("Truncated tar entry")
        return buffer.array().inputStream()
    }

    override fun close() = holder.close()

    private fun readFully(buffer: ByteBuffer, position: Long): Boolean = runCatching {
        val channel = holder.channel
        channel.position(position)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) return false
        }
        true
    }.getOrDefault(false)

    private fun ByteBuffer.cString(offset: Int, length: Int): String {
        val bytes = ByteArray(length)
        duplicate().apply { position(offset) }.get(bytes)
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) length else it }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun ByteBuffer.octal(offset: Int, length: Int): Long {
        val bytes = ByteArray(length)
        duplicate().apply { position(offset) }.get(bytes)
        return String(bytes, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }.toLongOrNull(8) ?: 0L
    }

    private companion object {
        const val TAR_BLOCK = 512
    }
}
