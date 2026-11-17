package com.screentranslator.search

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.screentranslator.gemini.GeminiProtocol

/** Display Google's provided HTML unchanged. No JS, local file/content access, bridge, storage or automatic navigation. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SearchSuggestions(html: String, modifier: Modifier, openLink: (String) -> Unit) {
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.hasGesture() && GeminiProtocol.httpsLink(request.url.toString())) openLink(request.url.toString())
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.scheme == "https") null else WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
            }
        }
    }, update = { view ->
        if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL("https://www.google.com/", html, "text/html", "UTF-8", null) }
    }, onRelease = { view -> view.stopLoading(); view.loadUrl("about:blank"); view.removeAllViews(); view.destroy() })
}
