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

import com.hippo.ehviewer.archive.FileArchiveChannel
import com.hippo.ehviewer.archive.PlainZipSession
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 页源是阅读器的数据入口，这里验证「压缩包 → 本地文件」这条链路 */
class ArchivePageSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun materializesPagesIntoSortedFiles() = runBlocking {
        val page = ByteArray(1024) { (it % 97).toByte() }
        val archive = temp.newFile("gallery.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("10.jpg", "1.jpg").forEachIndexed { index, name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(page)
                if (index == 1) zip.write(byteArrayOf(0x42))
                zip.closeEntry()
            }
        }

        val channel = FileArchiveChannel(archive)
        val zip = ZipFile.builder().setSeekableByteChannel(channel.channel).get()
        val cacheDir = temp.newFolder("cache")
        ArchivePageSource(PlainZipSession(zip, channel), cacheDir).use { source ->
            assertEquals(2, source.count)
            assertEquals(listOf("10.jpg", "1.jpg"), listOf(source.name(0), source.name(1)))

            val first = source.page(0)
            assertTrue(first.isFile)
            assertArrayEquals(page, first.readBytes())

            // 第二次必须命中缓存，不重新解压
            val again = source.page(0)
            assertEquals(first.absolutePath, again.absolutePath)

            val second = source.page(1)
            assertEquals(page.size + 1, second.readBytes().size)
        }
    }

    /** 压缩包给的是「页 → 条目下标」映射（只收图片 + 自然序），页源必须照映射取 */
    @Test
    fun honoursThePageToEntryMapping() = runBlocking {
        val archive = temp.newFile("gallery2.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("cover.txt", "2.jpg", "10.jpg").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }

        val channel = FileArchiveChannel(archive)
        val zip = ZipFile.builder().setSeekableByteChannel(channel.channel).get()
        val cacheDir = temp.newFolder("cache2")
        ArchivePageSource(PlainZipSession(zip, channel), cacheDir).use { source ->
            // 不传映射：全部非目录条目，按包内原顺序
            assertEquals(3, source.count)
            assertEquals("cover.txt", source.name(0))
        }

        // session 关了就得重开，不能复用同一个 ZipFile
        val channel2 = FileArchiveChannel(archive)
        val zip2 = ZipFile.builder().setSeekableByteChannel(channel2.channel).get()
        ArchivePageSource(PlainZipSession(zip2, channel2), cacheDir, listOf(2, 1)).use { source ->
            assertEquals(2, source.count)
            assertEquals("10.jpg", source.name(0))
            assertEquals("2.jpg", source.name(1))
            assertEquals("2.jpg", source.page(1).readBytes().decodeToString())
        }
    }
}
