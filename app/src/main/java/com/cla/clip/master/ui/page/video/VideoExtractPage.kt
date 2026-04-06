package com.cla.clip.master.ui.page.video

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.logD
import com.cla.clip.base.general.logI
import com.cla.clip.base.general.logW
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.delay

private const val HIDDEN_PROBE_TIMEOUT_MS = 6_000L
private const val USER_PLAY_TIMEOUT_MS = 25_000L

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

/** 视频提取页 */
@Composable
fun VideoExtractPage(
    videoExtractVm: VideoExtractVm = hiltViewModel(),
    pageUrl: String,
    onBack: () -> Unit,
) {

    var probeState by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }
    var sessionId by remember { mutableIntStateOf(0) }
    var targetUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

//    val link = "https://v.douyin.com/bzLHPnkAbhs/"
    sessionId += 1
    targetUrl = pageUrl // 这里改成你的真实 link
    probeState = ProbeState.HiddenProbing(sessionId)
    webViewRef?.stopLoading()
    webViewRef?.clearHistory()
    webViewRef?.loadUrl(pageUrl)
    logD("视频提取") { "开始探测: session=$sessionId, url=$pageUrl" }

    // 阶段1超时：后台探测 -> 需要用户播放
    LaunchedEffect(probeState) {
        val s = probeState
        if (s is ProbeState.HiddenProbing) {
            delay(HIDDEN_PROBE_TIMEOUT_MS)
            if (probeState is ProbeState.HiddenProbing &&
                (probeState as ProbeState.HiddenProbing).sessionId == s.sessionId
            ) {
                probeState = ProbeState.NeedUserPlay(s.sessionId)
                webViewRef?.evaluateJavascript(AUTO_PLAY_JS, null)
            }
        }
    }

    // 阶段2超时：用户播放阶段仍无结果 -> 失败
    LaunchedEffect(probeState) {
        val s = probeState
        if (s is ProbeState.NeedUserPlay) {
            delay(USER_PLAY_TIMEOUT_MS)
            if (probeState is ProbeState.NeedUserPlay &&
                (probeState as ProbeState.NeedUserPlay).sessionId == s.sessionId
            ) {
                probeState = ProbeState.Failed("超时仍未抓取到视频地址")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        TitleBar(stringResource(R.string.base_general_video_extract), onBack)


        // ========== 底层：WebView 层（始终存在，始终全尺寸） ==========
        VideoProbeWebViewLayer(
            targetUrl = targetUrl,
            onWebViewReady = { webViewRef = it },
            onVideoCandidate = { candidate ->
                if (probeState is ProbeState.HiddenProbing || probeState is ProbeState.NeedUserPlay) {
                    probeState = ProbeState.Success(candidate)
                    logI("视频提取") { "抓取成功: $candidate" }
                    // TODO 接你的下载逻辑：detailVm.startDownload(candidate)
                }
            }
        )

        // ========== 前景遮罩层：隐藏探测时盖住 WebView，并吞掉触摸 ==========
        val hideWebView = probeState is ProbeState.HiddenProbing || probeState is ProbeState.Idle
        if (hideWebView) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {} // 吞掉触摸，防止点到底层 WebView
            )
        }
        // NeedUserPlay 时不加全屏遮罩，让用户可直接操作 WebView


        when (val s = probeState) {
            ProbeState.Idle -> Text("等待开始")
            is ProbeState.HiddenProbing -> Text("后台探测中...")
            is ProbeState.NeedUserPlay -> Text("请在下方 WebView 中点击播放")
            is ProbeState.Success -> Text("抓取成功: ${s.candidate.url}")
            is ProbeState.Failed -> Text("抓取失败: ${s.reason}")
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoProbeWebViewLayer(
    targetUrl: String?,
    onWebViewReady: (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val latestCallback by rememberUpdatedState(onVideoCandidate)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // 尝试自动触发（成功率非100%）
                        if (view.progress > 99) {
                            logW("视频提取") { "onPageFinished: 去触发播放" }
                            view.evaluateJavascript(AUTO_PLAY_JS, null)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        val headers = request.requestHeaders.orEmpty()
//                        logD("视频提取") { "shouldInterceptRequest: reqUrl=$reqUrl" }
                        if (isLikelyVideoRequest(request.url, headers)) {
                            val cookie = android.webkit.CookieManager.getInstance().getCookie(reqUrl)

                            val candidate = VideoCandidate(
                                url = reqUrl,
                                referer = headers["Referer"],
                                userAgent = headers["User-Agent"],
                                cookie = cookie
                            )

//                            logD("视频提取") {
//                                """
//                                request.url=${request.url}
//                                candidate=$candidate
//                            """.trimIndent()
//                            }

                            mainHandler.post { onVideoCandidate(candidate) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                onWebViewReady(this)
            }
        },
        update = { webView ->
            // 只有 URL 变化时再加载，避免重组反复 loadUrl
            val current = webView.url
            if (!targetUrl.isNullOrBlank() && current != targetUrl) {
                webView.loadUrl(targetUrl)
            }
        }
    )
}

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
        logI("视频提取") { "isLikelyVideoRequest: 这是抖音的视频地址 uri=$uri" }
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

private fun toPlayUrl(url: String): String =
    url.replace("/playwm/", "/play/")

private fun toPlaywmUrl(url: String): String =
    url.replace("/play/", "/playwm/")

private sealed interface ProbeState {
    data object Idle : ProbeState
    data class HiddenProbing(val sessionId: Int) : ProbeState
    data class NeedUserPlay(val sessionId: Int) : ProbeState
    data class Success(val candidate: VideoCandidate) : ProbeState
    data class Failed(val reason: String) : ProbeState
}

private data class VideoCandidate(
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val cookie: String?,
)