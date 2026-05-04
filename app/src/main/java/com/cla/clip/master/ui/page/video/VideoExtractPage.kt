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
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toast
import com.cla.clip.base.general.widget.RequestStoragePermission
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val HIDDEN_PROBE_TIMEOUT_MS = 10_000L
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


/** 视频地址识别页 */
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun VideoExtractPage(
    videoExtractVm: VideoExtractVm = hiltViewModel(),
    pageUrl: String,
    pageName: String,
    onBack: () -> Unit,
    onNavigate: (route: Route) -> Unit
) {
    val tag = "视频地址识别"
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        videoExtractVm.createDownloadTaskFlow.collectLatest { taskId ->
            if (taskId < 0) {
                context.toast(R.string.base_general_failed_to_create_the_download_task)
            } else {
                onNavigate(VideoDownloadRoute(taskId))
            }
        }
    }

    LaunchedEffect(videoExtractVm.probeState) {
        when (val s = videoExtractVm.probeState) {
            // 阶段1超时：后台探测 -> 需要用户播放
            is ProbeState.HiddenProbing -> {
                delay(HIDDEN_PROBE_TIMEOUT_MS)
                val cur = videoExtractVm.probeState
                if (cur is ProbeState.HiddenProbing && cur.sessionId == s.sessionId) {
                    logD(tag) { "VideoExtractPage: 自动识别超时，显示错误提示" }
                    videoExtractVm.probeState = ProbeState.Failed
                    // todo 这里超时之后要显示webview，并引导用户主动点击视频播放，拦截视频的地址
//                    videoExtractVm.probeState = ProbeState.NeedUserPlay(s.sessionId)
//                    webViewRef?.evaluateJavascript(AUTO_PLAY_JS, null)
                }
            }

            // 阶段2超时：用户播放阶段仍无结果 -> 失败
            is ProbeState.NeedUserPlay -> {
                delay(USER_PLAY_TIMEOUT_MS)
                val cur = videoExtractVm.probeState
                if (cur is ProbeState.NeedUserPlay && cur.sessionId == s.sessionId) {
                    logD(tag) { "VideoExtractPage: 用户手动识别超时，显示错误提示" }
                    videoExtractVm.probeState = ProbeState.Failed
                }
            }

            else -> Unit
        }
    }

    fun clearWebView() {
        logD(tag) { "clearWebView : " }
        webViewRef?.webChromeClient = null
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.destroy()
        webViewRef = null
    }

    DisposableEffect(Unit) {
        onDispose { clearWebView() }
    }

    //    val link = "https://v.douyin.com/bzLHPnkAbhs/"
    LaunchedEffect(videoExtractVm.sessionId) {
        if (videoExtractVm.probeState is ProbeState.Failed || videoExtractVm.probeState is ProbeState.Success) {
            return@LaunchedEffect
        }

        videoExtractVm.probeState = ProbeState.HiddenProbing(videoExtractVm.sessionId)
        webViewRef?.stopLoading()
        webViewRef?.clearHistory()
        webViewRef?.loadUrl(pageUrl)
        logD(tag) { "开始探测: session=${videoExtractVm.sessionId}, url=$pageUrl" }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) {
        Box {
            if (videoExtractVm.probeState !is ProbeState.Success) {
                Column {
                    TitleBar(stringResource(R.string.base_general_video_extract), onBack)
                    VideoProbeWebViewLayer(
                        targetUrl = pageUrl,
                        pageName = pageName,
                        onWebViewReady = { webViewRef = it },
                        onVideoCandidate = { candidate ->
                            if (videoExtractVm.probeState !is ProbeState.Success) {
                                clearWebView()
                                logD(tag) { "VideoExtractPage: 视频地址识别成功 candidate=$candidate" }
                                videoExtractVm.probeState = ProbeState.Success(candidate)
                            }
                        }
                    )
                }
            }

            // ========== 前景遮罩层：盖住 WebView，并吞掉触摸，让WebView在后面加载页面，等页面加载完成之后，触发视频播放，拦截视频地址 ==========
//            val hideWebView = videoExtractVm.probeState is ProbeState.HiddenProbing || videoExtractVm.probeState is ProbeState.Idle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}, // 吞掉触摸，防止点到底层 WebView

            ) {
                TitleBar(stringResource(R.string.base_general_video_extract), onBack)

                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (val state = videoExtractVm.probeState) {
                        ProbeState.Idle -> {}

                        is ProbeState.HiddenProbing -> {
                            Loading()
                        }

                        is ProbeState.NeedUserPlay -> {
                            // todo 但这个先不做，因为webView播放视频一直是黑屏的状态，还不清楚是为什么
                            // NeedUserPlay 时不加全屏遮罩，让用户可直接操作 WebView
                            Text("请在下方 WebView 中点击播放")
                        }

                        is ProbeState.Failed -> {
                            Filed(
                                retry = { videoExtractVm.sessionId += 1 }
                            )
                        }

                        is ProbeState.Success -> {
                            Success(
                                videoExtractVm,
                                state.candidate,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 提取失败 */
@Composable
fun Filed(retry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = { retry() })
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

@Preview(showBackground = true)
@Composable
private fun FailedPreview() {
    val context = LocalContext.current
    ClipMaterTheme {
        Filed(
            retry = {
                Toast.makeText(context, "点击重试", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** 加载中状态 */
@Composable
private fun Loading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(25.dp),
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = stringResource(R.string.base_general_extract_the_video_address),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingPreview() {
    ClipMaterTheme {
        Loading()
    }
}

/** 视频地址识别成功 */
@Composable
private fun Success(
    videoExtractVm: VideoExtractVm,
    candidate: VideoCandidate,
) {
    var pendingCandidate by remember { mutableStateOf<Pair<Long, VideoCandidate>?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        pendingCandidate?.let { pending ->
            key(pending.first) {
                RequestStoragePermission(
                    next = {
                        pendingCandidate = null
                        // 创建下载任务，并且跳转详情页
                        videoExtractVm.startDownloadAndGo(pending.second)
                    }
                )
            }
        }

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
            onClick = { pendingCandidate = System.currentTimeMillis() to candidate }
        ) {
            Text(text = stringResource(R.string.base_general_to_download))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SuccessPreview() {
    ClipMaterTheme {
        Success(
            videoExtractVm = hiltViewModel(),
            candidate = VideoCandidate(
                "https://example.com/video.mp4",
                "https://example.com",
                "Mozilla/5.0",
                "cookie=value",
                ""
            ),
        )
    }
}

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
                    userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36" // Android手机访问
//                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36" // pc浏览器访问
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
                        // 尝试自动触发（成功率非100%）
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
            // 只有 URL 变化时再加载，避免重组反复 loadUrl
            val current = webView.url
            if (targetUrl.isNotBlank() && current != targetUrl) {
                webView.loadUrl(targetUrl)
            }
        }
    )
}

private fun isExternalAppScheme(scheme: String): Boolean {
    return scheme !in setOf("http", "https", "about", "data", "blob")
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

