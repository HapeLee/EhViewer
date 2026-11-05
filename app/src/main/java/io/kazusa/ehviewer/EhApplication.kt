/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kazusa.ehviewer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.collection.LruCache
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.ConnectivityChecker
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import coil3.util.DebugLogger
import io.kazusa.ehviewer.client.EhCookieStore
import io.kazusa.ehviewer.client.EhDns
import io.kazusa.ehviewer.client.EhEngine
import io.kazusa.ehviewer.client.EhFilter
import io.kazusa.ehviewer.client.EhSSLSocketFactory
import io.kazusa.ehviewer.client.EhTagDatabase
import io.kazusa.ehviewer.client.data.GalleryDetail
import io.kazusa.ehviewer.coil.DownloadThumbInterceptor
import io.kazusa.ehviewer.coil.MergeInterceptor
import io.kazusa.ehviewer.dao.buildMainDB
import io.kazusa.ehviewer.download.DownloadManager
import io.kazusa.ehviewer.support.okhttp.CloudflareInterceptor
import io.kazusa.ehviewer.support.util.ReadableTime
import io.kazusa.ehviewer.support.util.launchIO
import io.kazusa.ehviewer.support.util.loadHtml
import io.kazusa.ehviewer.support.yorozuya.FileUtils
import io.kazusa.ehviewer.support.yorozuya.IntIdGenerator
import io.kazusa.ehviewer.ui.EhActivity
import io.kazusa.ehviewer.ui.MainActivity
import io.kazusa.ehviewer.ui.host.DialogRequest
import io.kazusa.ehviewer.ui.keepNoMediaFileStatus
import io.kazusa.ehviewer.ui.reader.clearReaderCache
import io.kazusa.ehviewer.widget.SearchDatabase
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.DelicateCoroutinesApi
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class EhApplication :
    Application(),
    SingletonImageLoader.Factory {
    private val mIdGenerator = IntIdGenerator()
    private val mGlobalStuffMap = HashMap<Int, Any>()
    private val mActivityList = ArrayList<Activity>()
    val topActivity: EhActivity?
        get() = if (mActivityList.isNotEmpty()) {
            mActivityList[mActivityList.size - 1] as EhActivity
        } else {
            null
        }

    fun recreateAllActivity() {
        mActivityList.forEach { it.recreate() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        application = this
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                if (Settings.saveCrashLog) {
                    Crash.saveCrashLog(e)
                }
            } catch (_: Throwable) {
            }
            handler?.uncaughtException(t, e)
        }
        super.onCreate()
        Settings.initialize()
        ReadableTime.initialize(this)
        AppConfig.initialize(this)
        AppCompatDelegate.setDefaultNightMode(Settings.theme)

        launchIO {
            launchIO {
                nonCacheOkHttpClient
            }
            launchIO {
                EhTagDatabase.read()
            }
            launchIO {
                ehDatabase
            }
            launchIO {
                // 预热 DownloadManager：类初始化会读库，并把持久队列接回内存（P6-f）
                DownloadManager.isIdle
            }
            launchIO {
                // 过滤器是懒加载的，预热一下让类初始化落在 IO 线程
                EhFilter.warmUp()
            }
            launchIO {
                cleanupDownload()
            }
            launchIO {
                theDawnOfNewDay()
            }
        }
        mIdGenerator.setNextId(Settings.getInt(KEY_GLOBAL_STUFF_NEXT_ID, 0))
        if (BuildConfig.DEBUG) {
            StrictMode.enableDefaults()
        }
    }

    private suspend fun cleanupDownload() {
        runCatching {
            keepNoMediaFileStatus()
        }.onFailure {
            Log.e(TAG, "Error", it)
        }
        runCatching {
            clearTempDir()
        }.onFailure {
            Log.e(TAG, "Error", it)
        }
    }

    private suspend fun theDawnOfNewDay() {
        runCatching {
            if (Settings.requestNews && EhCookieStore.hasSignedIn()) {
                EhEngine.getNews(true)?.let { showEventPane(it) }
            }
        }.onFailure {
            Log.e(TAG, "Error", it)
        }
    }

    fun showEventPane(html: String) {
        if (Settings.hideHvEvents && html.contains("You have encountered a monster!")) {
            return
        }
        // 公告是 HTML：转成 Spanned 交给宿主对话框，链接由 DialogHost 里的 TextView 负责点击
        val activity = topActivity as? MainActivity ?: return
        activity.runOnUiThread {
            val rich = loadHtml(html)
            activity.showDialog(
                DialogRequest.Message(
                    title = null,
                    message = rich.toString(),
                    richMessage = rich,
                    positiveText = activity.getString(android.R.string.ok),
                    onPositive = {},
                ),
            )
        }
    }

    private fun clearTempDir() {
        var dir = AppConfig.getTempDir()
        if (null != dir) {
            FileUtils.deleteContent(dir)
        }
        dir = AppConfig.getExternalTempDir()
        if (null != dir) {
            FileUtils.deleteContent(dir)
        }
        // 阅读器缓存（cache/reader/<gid>）：正常退出时页源自删，进程被杀就留下了。
        // 启动时一定没有活跃阅读器，整体清一次最省事。
        clearReaderCache(this)
    }

    fun putGlobalStuff(o: Any): Int {
        val id = mIdGenerator.nextId()
        mGlobalStuffMap[id] = o
        Settings.putInt(KEY_GLOBAL_STUFF_NEXT_ID, mIdGenerator.nextId())
        return id
    }

    fun containGlobalStuff(id: Int): Boolean = mGlobalStuffMap.containsKey(id)

    fun removeGlobalStuff(id: Int): Any? = mGlobalStuffMap.remove(id)

    fun removeGlobalStuff(o: Any) {
        mGlobalStuffMap.values.removeAll(setOf(o))
    }

    fun registerActivity(activity: Activity) {
        mActivityList.add(activity)
    }

    fun unregisterActivity(activity: Activity) {
        mActivityList.remove(activity)
    }

    override fun newImageLoader(context: Context) = ImageLoader.Builder(context).apply {
        serviceLoaderEnabled(false)
        components {
            // minSdk 31，AnimatedImageDecoder 一定可用；默认会重写过小的 GIF 帧延迟，
            // 不再需要 jni 里的 rewriteGifSource 补丁
            add(AnimatedImageDecoder.Factory())
            add(
                NetworkFetcher.Factory(
                    networkClient = { coilOkHttpClient.asNetworkClient() },
                    connectivityChecker = { ConnectivityChecker.ONLINE },
                ),
            )
            add(MergeInterceptor)
            add(DownloadThumbInterceptor)
        }
        crossfade(300)
        diskCache(thumbCache)
        if (BuildConfig.DEBUG) logger(DebugLogger())
    }.build()

    companion object {
        private val TAG = EhApplication::class.java.simpleName
        private const val KEY_GLOBAL_STUFF_NEXT_ID = "global_stuff_next_id"

        lateinit var application: EhApplication
            private set

        val cacheDir by lazy { application.cacheDir.toOkioPath() }

        val ehProxySelector by lazy { EhProxySelector() }

        val nonCacheOkHttpClient by lazy {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = application.resources.openRawResource(R.raw.isrgrootx1).use {
                cf.generateCertificates(it).first() as X509Certificate
            }
            val certs = HandshakeCertificates.Builder()
                .addPlatformTrustedCertificates()
                .addTrustedCertificate(cert)
                .build()
            OkHttpClient.Builder().apply {
                cookieJar(EhCookieStore)
                dns(EhDns)
                proxySelector(ehProxySelector)
                sslSocketFactory(EhSSLSocketFactory(certs.sslSocketFactory()), certs.trustManager)
                addInterceptor(CloudflareInterceptor())
            }.build()
        }

        val noRedirectOkHttpClient by lazy {
            nonCacheOkHttpClient.newBuilder()
                .followRedirects(false)
                .build()
        }

        val coilOkHttpClient by lazy {
            nonCacheOkHttpClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val newRequest = request.newBuilder()
                        .header("User-Agent", Settings.userAgent!!)
                        .build()
                    chain.proceed(newRequest)
                }
                .build()
        }

        // Never use this okhttp client to download large blobs!!!
        val okHttpClient by lazy {
            nonCacheOkHttpClient.newBuilder()
                .cache(Cache(FileSystem.SYSTEM, cacheDir / "http_cache", 20 * 1024 * 1024))
                .build()
        }

        val galleryDetailCache by lazy {
            LruCache<Long, GalleryDetail>(25).also {
                favouriteStatusRouter.addListener { gid, slot ->
                    it[gid]?.favoriteSlot = slot
                }
            }
        }

        val favouriteStatusRouter by lazy { FavouriteStatusRouter() }

        val ehDatabase by lazy { buildMainDB(application) }

        val searchDatabase by lazy { SearchDatabase.getInstance(application)!! }

        val thumbCache by lazy {
            DiskCache.Builder()
                .directory(cacheDir / "thumb")
                .maxSizeBytes((Settings.readCacheSize / 5).coerceIn(64, 1024).toLong() * 1024 * 1024)
                .build()
        }

        val imageCache by lazy {
            DiskCache.Builder()
                .directory(cacheDir / "image")
                .maxSizeBytes((Settings.readCacheSize / 5 * 4).coerceIn(256, 4096).toLong() * 1024 * 1024)
                .build()
        }
    }
}
