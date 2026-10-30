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
package com.hippo.ehviewer.ui.scene

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.hippo.ehviewer.archive.ArchiveOpener
import com.hippo.ehviewer.ui.compose.EhComposeScene
import com.hippo.ehviewer.ui.compose.nav.EhRoute
import com.hippo.ehviewer.ui.reader.ArchivePageSource
import com.hippo.ehviewer.ui.reader.PageSource
import com.hippo.ehviewer.ui.reader.ReaderScreen
import java.io.File

/**
 * 阅读器场景：把「Uri → ArchiveSession → PageSource → ReaderScreen」串起来。
 *
 * 迁移期入口仍是 `GalleryActivity` 的 `ACTION_VIEW`；等在线/已下载页源补齐后，
 * 再把整条阅读路径切到这个场景，替换掉自绘 GL 的 `GalleryActivity` 实现。
 */
class ReaderScene : EhComposeScene() {

    private var pageSource: PageSource? = null
    private var descriptor: ParcelFileDescriptor? = null

    @Composable
    override fun EntryProviderScope<NavKey>.registerDestinations(backStack: MutableList<NavKey>) {
        entry<EhRoute.Reader> {
            val context = LocalContext.current
            var source by remember { mutableStateOf<PageSource?>(null) }
            var failure by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val uri = arguments?.getParcelable<Uri>(KEY_URI) ?: error("No uri passed to ReaderScene")
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("Cannot open $uri")
                    descriptor = pfd
                    val session = ArchiveOpener(context, pfd).open()
                    val cacheDir = File(context.cacheDir, "reader/${System.nanoTime()}")
                    ArchivePageSource(session, cacheDir)
                }.onSuccess {
                    pageSource = it
                    source = it
                }.onFailure {
                    failure = it.message ?: it.javaClass.simpleName
                }
            }

            val current = source
            if (current != null) {
                ReaderScreen(pageSource = current, onBack = { finish() })
            } else {
                Scaffold { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val message = failure
                        if (message == null) {
                            CircularProgressIndicator()
                        } else {
                            Text(text = message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { pageSource?.close() }
        pageSource = null
        runCatching { descriptor?.close() }
        descriptor = null
        super.onDestroy()
    }

    companion object {
        const val KEY_URI = "reader_uri"
    }
}
