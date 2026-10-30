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

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 纯 JVM 验证：ZIP / 7z / TAR 三种会话能列条目、能取到字节。
 * 这些测试不需要 NDK、不需要真机，`gradlew :app:testDebugUnitTest` 即可跑。
 */
class ArchiveSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val page = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun zipSessionReadsEntries() {
        val file = temp.newFile("test.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("1.jpg", "10.jpg").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(page)
                zip.closeEntry()
            }
        }

        val channel = FileArchiveChannel(file)
        val zip = ZipFile.builder().setSeekableByteChannel(channel.channel).setUseUnicodeExtraFields(true).get()
        PlainZipSession(zip, channel).use { session ->
            assertEquals(2, session.entries.size)
            assertEquals(listOf("1.jpg", "10.jpg"), session.entries.map { it.name })
            assertArrayEquals(page, session.openEntry(1).use { it.readBytes() })
        }
    }

    @Test
    fun sevenZSessionReadsEntries() {
        val file = temp.newFile("test.cb7")
        SevenZOutputFile(file).use { sevenZ ->
            listOf("0.jpg", "1.jpg").forEach { name ->
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = page.size.toLong()
                }
                sevenZ.putArchiveEntry(entry)
                sevenZ.write(page)
                sevenZ.closeArchiveEntry()
            }
        }

        val channel = FileArchiveChannel(file)
        val sevenZ = SevenZFile(channel.channel)
        SevenZSession(sevenZ, channel).use { session ->
            assertEquals(2, session.entries.size)
            assertArrayEquals(page, session.openEntry(0).use { it.readBytes() })
        }
    }

    @Test
    fun tarSessionReadsEntries() {
        val file = temp.newFile("test.cbt")
        TarArchiveOutputStream(file.outputStream()).use { tar ->
            listOf("1.png", "2.png").forEach { name ->
                val entry = TarArchiveEntry(name).apply { size = page.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(page)
                tar.closeArchiveEntry()
            }
        }

        TarArchiveSession(FileArchiveChannel(file), file.length()).use { session ->
            assertEquals(2, session.entries.size)
            assertEquals(listOf("1.png", "2.png"), session.entries.map { it.name })
            assertArrayEquals(page, session.openEntry(1).use { it.readBytes() })
        }
    }

    @Test
    fun sniffDetectsFormats() {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        assertEquals(ArchiveFormat.ZIP, ArchiveFormats.sniff(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormats.sniff(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))
        // RAR4 / RAR5 共用前缀，只用来报“不支持”，不区分版本
        assertEquals(ArchiveFormat.RAR, ArchiveFormats.sniff(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)))
        assertEquals(ArchiveFormat.RAR, ArchiveFormats.sniff(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)))
        assertEquals(ArchiveFormat.XZ, ArchiveFormats.sniff(bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)))
        assertEquals(ArchiveFormat.GZIP, ArchiveFormats.sniff(bytes(0x1F, 0x8B)))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormats.sniff(bytes(0x00, 0x01, 0x02)))
    }

    @Test
    fun naturalOrderSortsNumerically() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg")
        val sorted = names.sortedWith { a, b -> naturalCompareForTest(a, b) }
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), sorted)
    }

    @Test
    fun emptyZipIsNotBroken() {
        val file = temp.newFile("empty.cbz")
        ZipOutputStream(file.outputStream()).use { }
        val channel = FileArchiveChannel(file)
        val zip = ZipFile.builder().setSeekableByteChannel(channel.channel).get()
        PlainZipSession(zip, channel).use { session ->
            assertTrue(session.entries.isEmpty())
        }
    }

    private fun naturalCompareForTest(lhs: String, rhs: String): Int {
        var i = 0
        var j = 0
        while (i < lhs.length && j < rhs.length) {
            val a = lhs[i]
            val b = rhs[j]
            if (a.isDigit() && b.isDigit()) {
                var ni = i
                var nj = j
                while (ni < lhs.length && lhs[ni].isDigit()) ni++
                while (nj < rhs.length && rhs[nj].isDigit()) nj++
                val na = lhs.substring(i, ni).trimStart('0')
                val nb = rhs.substring(j, nj).trimStart('0')
                val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ni
                j = nj
            } else {
                if (a != b) return a.compareTo(b)
                i++
                j++
            }
        }
        return (lhs.length - i) - (rhs.length - j)
    }
}
