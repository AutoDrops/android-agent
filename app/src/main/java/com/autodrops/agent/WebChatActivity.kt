package com.autodrops.agent

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * In-app web chat / sign-in via a standard WebView. We ONLY render the official website and let the
 * user log in there; we NEVER read, scrape, or export cookies/tokens. Cookies stay inside the WebView's
 * own store so the session persists between visits, exactly like a browser tab.
 */
class WebChatActivity : ComponentActivity() {
    private lateinit var web: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = setOf("chatgpt.com", "chat.openai.com", "auth.openai.com", "grok.com", "x.ai", "accounts.google.com", "gemini.google.com", "google.com")
        fun ok(host: String?): Boolean { val h = host?.lowercase() ?: return false; return allowed.any { h == it || h.endsWith(".$it") } }
        val requested = intent.getStringExtra("url") ?: "https://chatgpt.com"
        val url = if (ok(runCatching { android.net.Uri.parse(requested).host }.getOrNull())) requested else "https://chatgpt.com"
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            WebView.setWebContentsDebuggingEnabled(false)
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    val u = req?.url
                    val h = u?.host
                    // Only https + approved sign-in domains; block file:, intent:, http:, and off-list hosts.
                    return if (u?.scheme == "https" && ok(h)) false else true
                }
            }
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        setContentView(web)
        web.loadUrl(url)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }
}
