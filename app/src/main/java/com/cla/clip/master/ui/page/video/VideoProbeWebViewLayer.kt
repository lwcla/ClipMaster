package com.cla.clip.master.ui.page.video

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.widget.ProbeWebView
import com.cla.clip.master.ui.widget.ProbeWebViewEvent
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

/**
 * 复用通用 ProbeWebView 的视频探测适配层。
 *
 * 自动模式透明且屏蔽交互；可见模式允许用户操作原实例，不重新加载、不改变候选规则。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SharedVideoProbeWebViewLayer(
    targetUrl: String,
    sessionId: Int,
    pageName: String,
    mode: VideoProbeMode,
    onSaveLinkPreview: suspend (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
    onLoadEvent: (ProbeWebViewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 会话诊断使用统一标签，只记录阶段和数值，不展开网页或候选数据。
    val tag = "VideoProbe"
    // 预览补全和候选回调使用探测层协程作用域，组件离开组合时取消未完成的任务。
    val coroutineScope = rememberCoroutineScope()
    // 站内导航及时取消上一文档的辅助预览，不把旧 DOM 当作正文补写。
    var previewJob by remember(sessionId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 每轮开始时间只用于低频诊断，不打印 URL 或候选内容。
    val startedAt = remember(sessionId) { android.os.SystemClock.elapsedRealtime() }
    // 显隐不触发此 effect，只有新轮次或离场才开始/释放探测资源。
    DisposableEffect(sessionId) {
        logD(tag) { "开始网页提取 sessionId=$sessionId" }
        onDispose {
            previewJob?.cancel()
            logD(tag) { "释放网页提取 sessionId=$sessionId durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}" }
        }
    }

    ProbeWebView(
        targetUrl = targetUrl,
        // 两种显示模式处在同一组合位置，改变透明度不更换网页身份。
        modifier = modifier.fillMaxSize().alpha(if (mode == VideoProbeMode.Interactive) 1f else 0f),
        // 同一原生实例动态切换触摸、焦点和无障碍许可。
        consumeUserTouch = mode == VideoProbeMode.Automatic,
        sessionId = sessionId,
        onWebViewReady = {},
        onDocumentStarted = { previewJob?.cancel() },
        // 仅记录阶段变化和去重后的错误，加载百分比不刷日志。
        onLoadEvent = { event ->
            when (event) {
                is ProbeWebViewEvent.Started -> logD(tag) { "主文档开始 sessionId=$sessionId documentId=${event.documentId}" }
                is ProbeWebViewEvent.Finished -> logD(tag) { "主文档完成 sessionId=$sessionId documentId=${event.documentId}" }
                is ProbeWebViewEvent.Failed -> logW(tag) { "网页加载错误 sessionId=$sessionId documentId=${event.documentId} reasonCode=${event.error.kind} code=${event.error.code}" }
                is ProbeWebViewEvent.ExternalAppBlocked -> logW(tag) { "已拦截外部应用跳转 sessionId=$sessionId documentId=${event.documentId}" }
                is ProbeWebViewEvent.Progress -> Unit
            }
            onLoadEvent(event)
        },
        // 正常主文档完成后补全预览并尝试触发视频请求，显隐本身不触发第二次播放。
        onPageFinished = { view, _ ->
            previewJob = coroutineScope.launch {
                // WebView 真实加载后补齐链接预览，解决首轮 OkHttp/Jsoup 被 403 拦截时列表只有域名兜底的问题。
                onSaveLinkPreview(view)
            }
            // 页面加载完成后尝试触发 video 播放，部分站点只有播放后才会请求真实视频资源。
            // WebView 加载进度达到完成时才执行原有自动播放脚本。
            val progress = view.progress
            if (progress > 99) {
                logD(tag) { "尝试触发视频请求 sessionId=$sessionId" }
                view.evaluateJavascript(AUTO_PLAY_JS, null)
            }
        },
        // 继续从网页请求中识别候选，保留下载需要的请求上下文。
        shouldInterceptRequest = { view, request ->
            // 当前资源地址用于视频判断；请求头用于恢复下载请求的来源和客户端上下文。
            val reqUrl = request.url.toString()
            val headers = request.requestHeaders.orEmpty()
            if (isLikelyVideoRequest(request.url, headers)) {
                // 候选资源的站点 Cookie 沿用既有鉴权透传，不因隐藏网页而清除。
                val cookie = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                view.post {
                    // 在 WebView 所在线程构造候选，交给页面完成成功状态切换和资源释放。
                    val candidate = VideoCandidate(
                        url = reqUrl,
                        referer = headers["Referer"],
                        userAgent = headers["User-Agent"],
                        cookie = cookie,
                        fileName = pageName
                    )
                    // 候选持有 Cookie/网页标题，只记录成功事实，不展开对象。
                    logD(tag) { "视频候选识别成功" }
                    // 候选立即驱动成功；预览只在页面完成时旁路补全，不阻塞主结果。
                    onVideoCandidate(candidate)
                }
            }
            null
        }
    )
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
        logI("视频地址识别") { "命中播放接口候选" }
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
