package com.cla.clip.master.ui.widget

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cla.clip.base.general.utils.logD

private const val DEFAULT_PROBE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

/** 判断是否为需要交给外部应用处理的 scheme，WebView 探测页会直接拦截避免跳出应用。 */
fun isExternalAppScheme(scheme: String): Boolean {
    return scheme !in setOf("http", "https", "about", "data", "blob")
}

/**
 * 通用网页探测 WebView，统一承载视频/图片提取页都需要的配置、Cookie 和请求拦截能力。
 *
 * 业务侧只需要通过回调处理页面加载完成和资源请求，避免在不同提取页复制一整套 WebView 设置。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProbeWebView(
    targetUrl: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    onWebViewReady: (WebView) -> Unit,
    onPageFinished: (WebView, String?) -> Unit,
    shouldInterceptRequest: (WebView, WebResourceRequest) -> WebResourceResponse?,
) {
    val tag = "ProbeWebView"

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply webView@{
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = DEFAULT_PROBE_USER_AGENT
                    javaScriptCanOpenWindowsAutomatically = true
                    allowContentAccess = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    builtInZoomControls = true
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setGeolocationEnabled(true)
                    setSupportMultipleWindows(true)
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@webView, true)
                }

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val reqUrl = request.url
                        val scheme = reqUrl.scheme?.lowercase().orEmpty()
                        if (request.isForMainFrame && isExternalAppScheme(scheme)) {
                            // 外部 App scheme 不参与资源探测，直接拦截避免页面把用户带出当前流程。
                            logD(tag) { "blocked non-web url: $reqUrl" }
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        onPageFinished(view, url)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return shouldInterceptRequest(view, request)
                    }
                }
                onWebViewReady(this)
            }
        },
        update = { webView ->
            val current = webView.url
            if (targetUrl.isNotBlank() && current != targetUrl) {
                // targetUrl 变化时才重新加载，避免 Compose 重组导致页面探测被反复重启。
                webView.loadUrl(targetUrl)
            }
        }
    )
}
