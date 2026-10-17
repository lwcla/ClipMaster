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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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

/**
 * 复用通用 ProbeWebView 的隐藏视频探测层。
 *
 * 完整视口用于网页加载和自动播放触发；透明显示并屏蔽用户触摸，让用户只操作前景提取内容。
 * 透明配置不停止网页探测，WebView 的重试和销毁仍由页面管理；当前不承载手动播放辅助流程。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SharedVideoProbeWebViewLayer(
    targetUrl: String,
    sessionId: Int,
    pageName: String,
    onSaveLinkPreview: suspend (WebView) -> Unit,
    onVideoCandidate: (VideoCandidate) -> Unit,
) {
    // 视频资源拦截日志沿用既有标签，隐藏配置不新增日志。
    val tag = "webView"
    // 预览补全和候选回调使用探测层协程作用域，组件离开组合时取消未完成的任务。
    val coroutineScope = rememberCoroutineScope()
    // 站内导航及时取消上一文档的辅助预览，不把旧 DOM 当作正文补写。
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    ProbeWebView(
        targetUrl = targetUrl,
        // 与图片提取页一致：保留真实视口继续探测，透明显示避免网页内容露出前景遮罩。
        modifier = Modifier.fillMaxSize().alpha(0f),
        // 用户触摸只交给前景页面，避免透明网页被误点、滚动或触发跳转。
        consumeUserTouch = true,
        sessionId = sessionId,
        onWebViewReady = {},
        onDocumentStarted = { previewJob?.cancel() },
        // 页面加载完成后补全预览并尝试触发视频请求，透明配置不改变原有执行时机。
        onPageFinished = { view, _ ->
            previewJob = coroutineScope.launch {
                // WebView 真实加载后补齐链接预览，解决首轮 OkHttp/Jsoup 被 403 拦截时列表只有域名兜底的问题。
                onSaveLinkPreview(view)
            }
            // 页面加载完成后尝试触发 video 播放，部分站点只有播放后才会请求真实视频资源。
            // WebView 加载进度达到完成时才执行原有自动播放脚本。
            val progress = view.progress
            if (progress > 99) {
                logW(tag) { "onPageFinished: progress=${progress}" }
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
