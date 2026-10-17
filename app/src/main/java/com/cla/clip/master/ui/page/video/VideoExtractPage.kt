package com.cla.clip.master.ui.page.video

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.master.ad.*
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.widget.PageBackground
import com.cla.clip.master.ui.widget.ProbeLifecycleEffect
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.media.MediaSourceSummary
import kotlinx.coroutines.delay

/** 视频识别页面只连接会话、隐藏网页及前景内容；成功、失败和不可见时都移除探测资源。 */
@Composable
fun VideoExtractPage(
    videoExtractVm: VideoExtractVm = hiltViewModel(),
    pageUrl: String,
    pageName: String,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    // 页面只读取一次会话快照，异步回调携带该快照的轮次。
    val state = videoExtractVm.probeState
    // 缓存补写后重新读取摘要，不为页面额外请求远端元数据。
    val previewFlow = remember(videoExtractVm, pageUrl, videoExtractVm.previewRevision) {
        videoExtractVm.observeCachedPreview(pageUrl)
    }
    // 已有网页摘要保留到结果或失败阶段。
    val cachedPreview by previewFlow.collectAsStateWithLifecycle(initialValue = null)
    // 权限流程期间保持广告布局稳定。
    var permissionPending by remember { mutableStateOf(false) }
    // 同页滚动状态不随提取阶段切换。
    val contentScroll = rememberScrollState()
    // 广告随探测轮次隔离，进度变化不重建。
    val adSession = rememberPageAdSession(pageUrl to videoExtractVm.sessionId)
    // 只有实际内容满足既有广告资格。
    val placement = videoExtractAdPlacement(state,
        !cachedPreview?.description.isNullOrBlank() || !cachedPreview?.imageUrl.isNullOrBlank())
    SideEffect {
        adSession.canMutateLayout = !contentScroll.isScrollInProgress && !permissionPending
        adSession.suppressed = permissionPending || placement == null
    }
    // 可见时恢复未完成会话；暂停只影响网页，不影响已提交的下载 Worker。
    ProbeLifecycleEffect(videoExtractVm.probeSession::resume, videoExtractVm.probeSession::pause)
    // 状态变化取消旧计时，失败立即关闭候选接收并移除 WebView。
    LaunchedEffect(state) {
        if (state is ProbeState.HiddenProbing) {
            delay(10_000L)
            videoExtractVm.probeSession.fail(state.sessionId)
        }
    }
    // 消费后再导航，避免组合恢复重复跳转。
    LaunchedEffect(videoExtractVm.createdTaskId) {
        videoExtractVm.createdTaskId?.let { taskId ->
            videoExtractVm.consumeCreatedTask()
            onNavigate(VideoDownloadRoute(taskId))
        }
    }
    SecondaryPageScaffold(title = stringResource(R.string.base_general_video_extract), onBack = onBack) { padding ->
        PageBackground(contentPadding = padding) {
            Box(Modifier.fillMaxSize()) {
                if (state is ProbeState.HiddenProbing) {
                    SharedVideoProbeWebViewLayer(
                        targetUrl = pageUrl,
                        sessionId = state.sessionId,
                        pageName = pageName,
                        onSaveLinkPreview = { view -> videoExtractVm.saveWebViewLinkPreview(view, pageUrl) },
                        onVideoCandidate = { candidate -> videoExtractVm.probeSession.accept(state.sessionId, candidate) },
                    )
                }
                Column(
                    modifier = Modifier.fillMaxSize().adInteractionGuard(adSession).verticalScroll(contentScroll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 摘要在失败时仍保留，避免用户丢失操作上下文。
                    val candidate = (state as? ProbeState.Success)?.candidate
                    MediaSourceSummary(
                        title = candidate?.fileName?.takeIf { it.isNotBlank() } ?: cachedPreview?.title ?: pageName,
                        source = Uri.parse(candidate?.referer ?: pageUrl).host,
                        description = cachedPreview?.description,
                        imageUrl = cachedPreview?.imageUrl,
                    )
                    when (state) {
                        ProbeState.Idle, is ProbeState.HiddenProbing -> Loading()
                        ProbeState.Failed -> Filed(videoExtractVm.probeSession::retry)
                        is ProbeState.Success -> Success(videoExtractVm, state.candidate) { permissionPending = it }
                    }
                    placement?.let { adPlacement ->
                        PageAdSlot(adSession, adPlacement, requestEnabled = !permissionPending,
                            canReveal = !permissionPending && !contentScroll.isScrollInProgress,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}