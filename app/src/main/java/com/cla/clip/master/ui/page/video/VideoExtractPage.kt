package com.cla.clip.master.ui.page.video

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.widget.TitleBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** 隐藏 WebView 自动探测的最长等待时间，单位毫秒；超过后认为当前轮次没有捕获到视频资源。 */
private const val HIDDEN_PROBE_TIMEOUT_MS = 10_000L

/** 预留给用户手动播放阶段的最长等待时间，单位毫秒；当前阶段主要作为后续交互增强的状态边界。 */
private const val USER_PLAY_TIMEOUT_MS = 25_000L

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
                        onSaveLinkPreview = { view ->
                            videoExtractVm.saveWebViewLinkPreview(view, pageUrl)
                        },
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
