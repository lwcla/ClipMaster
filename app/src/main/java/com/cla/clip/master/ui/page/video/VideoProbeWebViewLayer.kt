package com.cla.clip.master.ui.page.video

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.widget.ProbeWebView
import kotlinx.coroutines.launch

/**
 * 自动播放页面内 video 标签的脚本。
 *
 * 静音和 playsInline 可以提升移动站点允许自动播放的概率；失败时吞掉 Promise 异常，避免 WebView 控制台错误影响探测流程。
 */
private const val AUTO_PLAY_JS = """
(function() {
  try {
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      v.muted = true;
      v.playsInline = true;
      var p = v.play();
      if (p && p.catch) { p.catch(function(){}); }
    }
  } catch (e) {}
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SharedVideoProbeWebViewLayer(
    targetUrl: String,
    pageName: String,
    onWebViewReady: (WebView) -> Unit,
    onSaveLinkPreview: suspend (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
) {
    val tag = "webView"
    val coroutineScope = rememberCoroutineScope()

    /**
     * 复用通用 ProbeWebView 的视频探测层。
     *
     * 当前页面只关心请求拦截和候选地址回调，具体 WebView 配置集中在 `ProbeWebView` 中维护。
     */
    ProbeWebView(
        targetUrl = targetUrl,
        onWebViewReady = onWebViewReady,
        onPageFinished = { view, _ ->
            coroutineScope.launch {
                // WebView 真实加载后补齐链接预览，解决首轮 OkHttp/Jsoup 被 403 拦截时列表只有域名兜底的问题。
                onSaveLinkPreview(view)
            }
            // 页面加载完成后尝试触发 video 播放，部分站点只有播放后才会请求真实视频资源。
            val progress = view.progress
            if (progress > 99) {
                logW(tag) { "onPageFinished: progress=${progress}" }
                view.evaluateJavascript(AUTO_PLAY_JS, null)
            }
        },
        shouldInterceptRequest = { view, request ->
            val reqUrl = request.url.toString()
            val headers = request.requestHeaders.orEmpty()
            logD(tag) { "shouldInterceptRequest: reqUrl=$reqUrl" }
            if (isLikelyVideoRequest(request.url, headers)) {
                val cookie = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                view.post {
                    val candidate = VideoCandidate(
                        url = reqUrl,
                        referer = headers["Referer"],
                        userAgent = headers["User-Agent"],
                        cookie = cookie,
                        fileName = pageName
                    )
                    logD(tag) { "candidate=$candidate " }
                    coroutineScope.launch {
                        // 候选命中后页面即将销毁，先抓取一次 DOM 预览，避免错过 WebView 阶段的封面信息。
                        onSaveLinkPreview(view)
                        onVideoCandidate(candidate)
                    }
                }
            }
            null
        }
    )
}

/**
 * 旧版内联 WebView 探测层。
 *
 * 保留用于对比通用 ProbeWebView 的行为差异；如果通用组件稳定覆盖所有站点，这段可以后续删除。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoProbeWebViewLayer(
    targetUrl: String,
    pageName: String,
    onWebViewReady: (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
) {
    val tag = "webView"

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
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
                    // 使用 Android 移动 UA，让短视频站点返回移动端播放器资源，命中真实视频请求的概率更高。
                    userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
//                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" // pc浏览器访问
                    javaScriptCanOpenWindowsAutomatically = true
                    allowContentAccess = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // 页面通常被前景遮罩覆盖，保留缩放/宽视口能力只用于调试手动播放阶段。
                    builtInZoomControls = true
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // 部分站点会根据定位权限或 API 判断播放能力，这里只开启 WebView 能力，不主动申请系统定位权限。
                    setGeolocationEnabled(true)
                    setSupportMultipleWindows(true)
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                }

                val cm = android.webkit.CookieManager.getInstance()
                // 第三方 Cookie 可能参与防盗链鉴权，下载任务会保存拦截时拿到的 Cookie。
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val reqUrl = request.url
                        val scheme = reqUrl.scheme?.lowercase().orEmpty()
                        if (request.isForMainFrame && isExternalAppScheme(scheme)) {
                            logD(tag) { "blocked non-web url: $reqUrl" }
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // 页面加载完成后尝试触发播放，成功率取决于站点的自动播放策略。
                        val progress = view.progress
                        if (progress > 99) {
                            logW(tag) { "onPageFinished: 去触发播放 progress=${progress}" }
                            view.evaluateJavascript(AUTO_PLAY_JS, null)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val headers = request.requestHeaders.orEmpty()
                        logD(tag) { "shouldInterceptRequest: reqUrl=$reqUrl" }
                        if (isLikelyVideoRequest(request.url, headers)) {
                            val cookie = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                            view.post {
                                val candidate = VideoCandidate(
                                    url = reqUrl,
                                    referer = headers["Referer"],
                                    userAgent = headers["User-Agent"],
                                    cookie = cookie,
                                    fileName = pageName
                                )
                                logD(tag) { "candidate=$candidate " }
                                onVideoCandidate(candidate)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                onWebViewReady(this)
            }
        },
        update = { webView ->
            // 只有 URL 变化时再加载，避免重组反复 loadUrl。
            val current = webView.url
            if (targetUrl.isNotBlank() && current != targetUrl) {
                webView.loadUrl(targetUrl)
            }
        }
    )
}

/** 判断 URL scheme 是否应交给外部 App；探测页只允许 WebView 继续处理网页相关 scheme。 */
private fun isExternalAppScheme(scheme: String): Boolean {
    return scheme !in setOf("http", "https", "about", "data", "blob")
}

/**
 * 判断一次 WebView 请求是否可能是真实视频资源。
 *
 * 规则结合平台接口特征、常见视频扩展名、关键词和 MIME；宁可接受少量误判也要尽早捕获会话 Cookie 和 Referer，
 * 后续下载失败再由 Worker/下载页反馈。
 */
private fun isLikelyVideoRequest(
    uri: Uri,
    headers: Map<String, String>,
): Boolean {
    val host = uri.host?.lowercase().orEmpty()
    val url = uri.toString().lowercase()
    val path = uri.encodedPath?.lowercase().orEmpty()

    // 1) 抖音/字节播放接口特征（高优先级）
    val isDouyinPlayApi = (host.contains("iesdouyin.com") || host.contains("douyin.com"))
            && (path.contains("/aweme/"))
            && (path.contains("/playwm/") || path.contains("/play/"))
            && (uri.getQueryParameter("video_id") != null)
    if (isDouyinPlayApi) {
        logI("视频地址识别") { "isLikelyVideoRequest: 这是抖音的视频地址 uri=$uri" }
        return true
    }

    val byExt = listOf(".m3u8", ".mp4", ".mpd", ".webm", ".flv", ".ts")
        .any { path.contains(it) || url.contains(it) }

    val byKeyword = listOf("video", "play", "stream", "playlist", "hls", "dash")
        .any { url.contains(it) }

    val accept = headers["Accept"]?.lowercase().orEmpty()
    val contentType = headers["Content-Type"]?.lowercase().orEmpty()

    val byMime = accept.contains("video/") ||
            accept.contains("application/vnd.apple.mpegurl") ||
            accept.contains("application/dash+xml") ||
            contentType.contains("video/") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/dash+xml")

    return byExt || (byKeyword && byMime) || byMime
}
