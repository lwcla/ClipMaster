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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.ui.theme.ClipMaterTheme
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

@Preview
@Composable
private fun VideoExtractPagePreview() {
    ClipMaterTheme {
        VideoExtractPage(
            pageUrl = "",
            onBack = {}
        )
    }
}

/** 视频提取页 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
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
                probeState = ProbeState.Failed
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

    //    val link = "https://v.douyin.com/bzLHPnkAbhs/"
    LaunchedEffect(Unit) {
        sessionId += 1
        targetUrl = pageUrl // 这里改成你的真实 link
//        probeState = ProbeState.HiddenProbing(sessionId)
        probeState = ProbeState.Success(VideoCandidate("", "", "", ""))
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.loadUrl(pageUrl)
        logD("视频提取") { "开始探测: session=$sessionId, url=$pageUrl" }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) {
        Box {
//            Column {
//                TitleBar(stringResource(R.string.base_general_video_extract), onBack)
//                VideoProbeWebViewLayer(
//                    targetUrl = targetUrl,
//                    onWebViewReady = { webViewRef = it },
//                    onVideoCandidate = { candidate ->
//                        if (probeState is ProbeState.HiddenProbing || probeState is ProbeState.NeedUserPlay) {
//                            probeState = ProbeState.Success(candidate)
//                            logI("视频提取") { "抓取成功: $candidate" }
//                            // TODO 接你的下载逻辑：detailVm.startDownload(candidate)
//                        }
//                    }
//                )
//            }

            // ========== 前景遮罩层：盖住 WebView，并吞掉触摸，让WebView在后面加载页面，等页面加载完成之后，触发视频播放，拦截视频地址 ==========
//            val hideWebView = probeState is ProbeState.HiddenProbing || probeState is ProbeState.Idle
//            if (hideWebView) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}, // 吞掉触摸，防止点到底层 WebView
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TitleBar(stringResource(R.string.base_general_video_extract), onBack)

                when (val state = probeState) {
                    ProbeState.Idle -> {}

                    is ProbeState.HiddenProbing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(
                                text = stringResource(R.string.base_general_extract_the_video_address),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    is ProbeState.NeedUserPlay -> {
                        // todo 但这个先不做，因为webView播放视频一直是黑屏的状态，还不清楚是为什么
                        // NeedUserPlay 时不加全屏遮罩，让用户可直接操作 WebView
                        Text("请在下方 WebView 中点击播放")
                    }

                    is ProbeState.Failed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = rememberVectorPainter(Icons.Default.Error),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .size(24.dp)
                            )
                            Text(
                                stringResource(R.string.base_general_failed_to_extract_the_video_address),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                            )
                        }
                    }

                    is ProbeState.Success -> {
                        VideoProbeSuccess(
                            state.candidate,
                            toDownload = { candidate ->
                                probeState = ProbeState.Download(DownloadResult(0, isFailed = false, isComplete = false))
                                // 开始下载视频
                            }
                        )
                    }

                    is ProbeState.Download -> {
                        VideoDownload(state.result)
                    }
                }
//
            }
        }
    }
}

/** 视频地址提取成功 */
@Composable
private fun VideoProbeSuccess(
    candidate: VideoCandidate,
    toDownload: (VideoCandidate) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = rememberVectorPainter(Icons.Default.Done),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(12.dp)
                    .size(24.dp)
            )
            Text(
                stringResource(R.string.base_general_extract_the_video_address_success),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }

        Button(
            onClick = { toDownload(candidate) }
        ) {
            Text(text = stringResource(R.string.base_general_to_download))
        }
    }
}

@Composable
private fun VideoDownload(result: DownloadResult) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val progress = result.progress.coerceIn(0, 100)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress.toFloat() / 100 },
                modifier = Modifier
                    .weight(1f)
            )

            Text(
                text = "${progress}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        if (result.isComplete) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = rememberVectorPainter(Icons.Default.DownloadDone),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp)
                )

                Text(
                    text = stringResource(R.string.base_general_video_download_success),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        } else if (result.isFailed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = rememberVectorPainter(Icons.Default.Error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp)
                )

                Text(
                    text = stringResource(R.string.base_general_video_download_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
        }
    }
}

@Preview(
    backgroundColor = 0xFFFFFFFF,
    showBackground = true
)
@Composable
private fun VideoDownloadPreview() {
    VideoDownload(DownloadResult(0, false, true))
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoProbeWebViewLayer(
    targetUrl: String?,
    onWebViewReady: (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
) {
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
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
                    javaScriptCanOpenWindowsAutomatically = true
                    allowContentAccess = true
                    allowFileAccess = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    builtInZoomControls = true // 隐藏缩放按钮
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL // 排版适应屏幕
                    useWideViewPort = true // 可任意比例缩放
                    loadWithOverviewMode = true // 设置webview加载的页面的模式
                    setGeolocationEnabled(true) // 启动地理定位
                    setSupportMultipleWindows(true)
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                }

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
                            onVideoCandidate(candidate)
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
    data object Failed : ProbeState
    data class Download(val result: DownloadResult) : ProbeState
}

private data class VideoCandidate(
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val cookie: String?,
)

private data class DownloadResult(
    val progress: Int,
    val isFailed: Boolean,
    val isComplete: Boolean
)