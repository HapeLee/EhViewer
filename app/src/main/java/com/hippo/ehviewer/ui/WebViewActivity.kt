package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.ui.compose.theme.ehWindowBackground
import com.hippo.ehviewer.util.addQuicHints
import com.hippo.ehviewer.util.setDefaultSettings

class WebViewActivity : EhActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.extras?.getString(KEY_URL) ?: return
        // WebView 自己默认是白底：深色主题下会先闪一帧白，页面加载完还可能露白边。
        // 窗口和 WebView 都按当前色板铺一次底（这里没有 Compose，取不了 LocalContext）
        val background = ehWindowBackground(this)
        window.setBackgroundDrawable(ColorDrawable(background))
        webView = WebView(applicationContext).apply {
            setBackgroundColor(background)
            setDefaultSettings()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val cloudflareBypassed = EhCookieStore.saveFromWebView(url) {
                        it.name == EhCookieStore.KEY_CLOUDFLARE
                    }
                    if (cloudflareBypassed) {
                        finish()
                    }
                }
            }
        }
        setContentView(webView)
        EhCookieStore.loadForWebView(url) {
            it.name != EhCookieStore.KEY_CLOUDFLARE
        }
        addQuicHints(url)
        webView!!.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }

    companion object {
        const val KEY_URL = "url"

        fun newIntent(context: Context, url: String): Intent = Intent(context, WebViewActivity::class.java).apply {
            putExtra(KEY_URL, url)
        }
    }
}
