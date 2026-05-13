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
import com.cla.clip.master.ui.widget.ProbeWebView
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** 隐藏 WebView 自动探测的最长等待时间，单位毫秒；超过后认为当前轮次没有捕获到视频资源。 */
private const val HIDDEN_PROBE_TIMEOUT_MS = 10_000L

/** 预留给用户手动播放阶段的最长等待时间，单位毫秒；当前阶段主要作为后续交互增强的状态边界。 */
private const val USER_PLAY_TIMEOUT_MS = 25_000L

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


/**
 * 视频地址识别页。
 *
 * 页面在后台加载目标网页，通过 WebView 请求拦截识别真实视频资源；识别成功后创建下载任务并跳转下载页。
 * WebView 生命周期在页面内管理，离开页面时必须销毁，避免继续加载第三方页面或持有 Activity。
 */
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

    /**
     * 当前探测用 WebView 引用。
     *
     * 仅在页面生命周期内持有，用于重试加载、执行自动播放脚本和退出时释放资源。
     */
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

    /**
     * 清理探测 WebView。
     *
     * 先解绑 WebChromeClient 再停止加载和销毁，降低页面退出后异步回调继续访问 Compose 状态的风险。
     */
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
                    SharedVideoProbeWebViewLayer(
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

            // 前景遮罩盖住 WebView 并吞掉触摸，让后台页面完成加载、触发播放并被请求拦截识别。
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
                            Text(stringResource(R.string.base_general_tap_video_in_webview))
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

/** 视频地址提取失败状态，点击整行会触发新一轮 session 重试。 */
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

/** 视频地址探测中的前景提示。 */
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

/**
 * 视频地址识别成功状态。
 *
 * 点击下载前先走存储权限申请；pendingCandidate 额外带时间戳，是为了同一个候选地址重复点击也能重新触发权限组件。
 */
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
                        // 权限确认后再创建下载任务，避免 Worker 启动后才发现没有保存权限。
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
private fun SharedVideoProbeWebViewLayer(
    targetUrl: String,
    pageName: String,
    onWebViewReady: (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
) {
    val tag = "webView"

    /**
     * 复用通用 ProbeWebView 的视频探测层。
     *
     * 当前页面只关心请求拦截和候选地址回调，具体 WebView 配置集中在 `ProbeWebView` 中维护。
     */
    ProbeWebView(
        targetUrl = targetUrl,
        onWebViewReady = onWebViewReady,
        onPageFinished = { view, _ ->
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
                    onVideoCandidate(candidate)
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
            // 只有 URL 变化时再加载，避免重组反复 loadUrl
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
