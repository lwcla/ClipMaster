package com.cla.clip.master.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
 * 显隐通过 modifier 与动态触摸配置表达，不能参与实例 key；加载诊断可选订阅，不改变图片页业务契约。
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
    onLoadEvent: (ProbeWebViewEvent) -> Unit = {},
) {
    // 每轮资源与回调闭包绑定；重试和恢复创建新实例，不把旧网络请求转交新会话。
    key(targetUrl, sessionId) {
        // 一个 WebView 对应一个加载门闩，合法重定向不会改变已请求的目标。
        val session = remember { ProbeLoadSession() }
        // STOP 时直接释放网页，避免后台重组被暂停后仍播放或请求资源。
        val owner = remember { ProbeWebViewOwner(session) }
        // 生命周期只负责资源许可，业务恢复仍由页面创建新的 session。
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, owner) {
            // 主线程事件与组合释放共用幂等所有者。
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) owner.release()
            }
            lifecycle.addObserver(observer)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) owner.release()
            onDispose {
                lifecycle.removeObserver(observer)
                owner.release()
            }
        }
        AndroidView(
            modifier = modifier,
            // 平台 Context 只由当前组合内的实例使用，配置不随显隐重复创建。
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

                    webChromeClient = object : WebChromeClient() {
                        /** 百分比只属于有效当前文档，不逐回调输出日志。 */
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            if (session.acceptsDocumentEvent(view.url, view.url, true)) {
                                onLoadEvent(ProbeWebViewEvent.Progress(session.documentId, newProgress))
                            }
                        }
                    }
                    webViewClient = ProbeWebViewClient(session, onDocumentStarted, onPageFinished,
                        shouldInterceptRequest, onLoadEvent)
                    owner.attach(this)
                    onWebViewReady(this)
                }
            },
            // 实例离开组合即释放；页面不再自行 destroy，避免双重销毁及悬挂 AndroidView。
            onRelease = { owner.release() },
            // 每次重组同步交互状态，展开后不保留 factory 捕获的旧触摸屏蔽。
            update = { webView ->
                // 已被 STOP 清理的旧实例等待页面换 session，不能继续调用平台方法。
                if (!session.acceptsCallbacks) return@AndroidView
                if (consumeUserTouch && webView.hasFocus()) {
                    // 只在网页持有焦点时收键盘，避免隐藏探测影响其他输入框。
                    val keyboard = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    keyboard?.hideSoftInputFromWindow(webView.windowToken, 0)
                    webView.clearFocus()
                }
                // 隐藏网页不能通过触摸、硬件键盘或无障碍被误操作；显示仍保留原实例。
                webView.setOnTouchListener(if (consumeUserTouch) View.OnTouchListener { _, _ -> true } else null)
                webView.isFocusable = !consumeUserTouch
                webView.isFocusableInTouchMode = !consumeUserTouch
                webView.descendantFocusability = if (consumeUserTouch) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
                webView.importantForAccessibility = if (consumeUserTouch) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                if (session.shouldLoad(targetUrl)) {
                    webView.loadUrl(targetUrl)
                }
            }
        )
    }
}
