package com.cla.clip.master.ui.widget

import android.annotation.SuppressLint
import android.view.MotionEvent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// 统一移动浏览器标识，避免两页发起不同网页布局。
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
    sessionId: Int = 0,
    modifier: Modifier = Modifier.fillMaxSize(),
    consumeUserTouch: Boolean = false,
    onWebViewReady: (WebView) -> Unit,
    onPageFinished: (WebView, String?) -> Unit,
    shouldInterceptRequest: (WebView, WebResourceRequest) -> WebResourceResponse?,
    onDocumentStarted: () -> Unit = {},
) {
    // 每轮资源与回调闭包绑定；重试和恢复创建新实例，不把旧网络请求转交新会话。
    key(targetUrl, sessionId) {
        // 一个 WebView 对应一个加载门闩，合法重定向不会改变已请求的目标。
        val session = remember { ProbeLoadSession() }
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

                    if (consumeUserTouch) {
                        setOnTouchListener { _, event ->
                            // 图片探测 WebView 只允许程序化 JS 滚动和资源加载；用户触摸会改变滚动位置、焦点或触发网页跳转，影响自动探测结果。
                            event.actionMasked in setOf(
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE,
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL,
                                MotionEvent.ACTION_POINTER_DOWN,
                                MotionEvent.ACTION_POINTER_UP
                            )
                        }
                    }

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        /** 主文档导航切断上一文档的扫描，后续完成回调属于新一代文档。 */
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (session.acceptsCallbacks) {
                                session.beginDocument(url)
                                onDocumentStarted()
                            }
                        }
                        /** 主文档只拦截外部应用协议，保留站内重定向。 */
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // 仅用 scheme 判断外部跳转，不记录完整用户地址。
                            val reqUrl = request.url
                            // 协议按大小写无关规则判断，普通网络重定向保持可用。
                            val scheme = reqUrl.scheme?.lowercase().orEmpty()
                            if (request.isForMainFrame && isExternalAppScheme(scheme)) {
                                // 外部 App scheme 不参与资源探测，直接拦截避免页面把用户带出当前流程。
                                return true
                            }
                            return false
                        }

                        /** 主文档完成仅通知当前实例一次，业务侧仍校验会话身份。 */
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            // 重定向中间页和重复完成不启动第二轮扫描，释放后的回调直接丢弃。
                            if (session.claimPageFinished(url, view.url)) onPageFinished(view, url)
                        }

                        /** 网络线程只向未释放实例发送请求，不转发给后续轮次。 */
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            // 原始闭包保留当前实例的轮次；主线程发布结果还需业务层再次校验。
                            return if (session.acceptsCallbacks) shouldInterceptRequest(view, request) else null
                        }
                    }
                    onWebViewReady(this)
                }
            },
            // 实例离开组合即释放；页面不再自行 destroy，避免双重销毁及悬挂 AndroidView。
            onRelease = { webView ->
                session.close()
                webView.stopLoading()
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.destroy()
            },
            update = { webView ->
                if (session.shouldLoad(targetUrl)) {
                    webView.loadUrl(targetUrl)
                }
            }
        )
    }
}
