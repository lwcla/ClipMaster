package com.cla.clip.master.ui.page.video

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.ad.*
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.VideoDownloadRoute
import com.cla.clip.master.ui.widget.PageBackground
import com.cla.clip.master.ui.widget.ProbeLifecycleEffect
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.media.MediaAdFooter
import com.cla.clip.master.ui.widget.media.MediaSourceSummary
import kotlinx.coroutines.delay

/** 视频页编排摘要、状态、固定广告底栏与单一网页宿主；结果独立滚动，生命周期决定资源许可。 */
@Composable
fun VideoExtractPage(
    videoExtractVm: VideoExtractVm = hiltViewModel(),
    pageUrl: String,
    pageName: String,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    // 会话保存轻量状态，网页原生实例只由下方 Compose 宿主持有。
    val session = videoExtractVm.probeSession
    // 一次读取阶段快照，所有旧实例回调仍携带此快照中的 sessionId。
    val state = session.state
    // 网页百分比与计时版本分离，诊断重组不会延长十秒预算。
    val timerVersion = session.timerVersion
    // 只有运行中的交互模式展示网页，成功后立即收起并释放。
    val interactive = (state as? ProbeState.Probing)?.mode == VideoProbeMode.Interactive
    // 缓存补写后重新读取摘要，不额外请求远端封面。
    val previewFlow = remember(videoExtractVm, pageUrl, videoExtractVm.previewRevision) {
        videoExtractVm.observeCachedPreview(pageUrl)
    }
    // 缓存读取跟随前台生命周期，不为标题新增数据库持续观察。
    val cachedPreview by previewFlow.collectAsStateWithLifecycle(initialValue = null)
    // 下载权限阶段抑制广告和重复提交。
    var permissionPending by remember { mutableStateOf(false) }
    // 摘要与自动模式内容分别滚动，网页始终获得有界的剩余高度。
    val summaryScroll = rememberScrollState()
    val controlsScroll = rememberScrollState()
    val resultScroll = rememberScrollState()
    // 每个提取轮次沿用现有广告会话，显隐不创建广告身份。
    val adSession = rememberPageAdSession(pageUrl to session.sessionId)
    // 取消封面后，仅实际显示的描述或候选构成广告资格。
    val placement = videoExtractAdPlacement(state, !cachedPreview?.description.isNullOrBlank())
    SideEffect {
        adSession.canMutateLayout = !resultScroll.isScrollInProgress && !permissionPending && !interactive
        adSession.suppressed = permissionPending || placement == null || interactive
    }
    // STOP 立即关闭业务轮次；共享 WebView 同时直接释放，避免等待后台重组。
    ProbeLifecycleEffect(
        onStart = {
            session.resume()
            logD("VideoProbe") { "页面恢复 sessionId=${session.sessionId} mode=${session.mode}" }
        },
        onStop = {
            session.pause()
            logD("VideoProbe") { "页面暂停 sessionId=${session.sessionId}" }
        },
    )
    // 显隐或预算变化取消旧任务；网页进度和错误提示不在 effect key 中。
    LaunchedEffect(state, timerVersion) {
        if (state is ProbeState.Probing) {
            if (state.mode == VideoProbeMode.Automatic) {
                delay(10_000L)
                session.expire(state.sessionId, timerVersion)
                if (session.state is ProbeState.Failed) {
                    logW("VideoProbe") { "自动提取超时 sessionId=${state.sessionId} reasonCode=no_candidate_timeout" }
                }
            } else {
                delay(30_000L)
                session.onLongRunning(state.sessionId, timerVersion)
            }
        }
    }
    // 下载任务事件先消费再导航，避免配置恢复后重复跳转。
    LaunchedEffect(videoExtractVm.createdTaskId) {
        videoExtractVm.createdTaskId?.let { taskId ->
            videoExtractVm.consumeCreatedTask()
            onNavigate(VideoDownloadRoute(taskId))
        }
    }
    SecondaryPageScaffold(title = stringResource(R.string.base_general_video_extract), onBack = onBack) { padding ->
        PageBackground(
            // 骨架已消费普通导航栏；三星零高导航栏仍有必需手势区，仅补尚未消费的底部安全距离。
            modifier = Modifier.imePadding()
                .windowInsetsPadding(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom)),
            contentPadding = padding,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 小屏/大字体时给摘要和操作区设置独立上限，至少保留剩余区域供网页使用。
                val summaryLimit = maxHeight * 0.30f
                val controlsLimit = maxHeight * 0.40f
                Column(Modifier.fillMaxSize().adInteractionGuard(adSession),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 成功后名称仍按原优先级选择，封面只在本页传空，公共摘要继续限制三行标题。
                    val candidate = (state as? ProbeState.Success)?.candidate
                    MediaSourceSummary(
                        title = candidate?.fileName?.takeIf { it.isNotBlank() } ?: cachedPreview?.title ?: pageName,
                        source = Uri.parse(candidate?.referer ?: pageUrl).host,
                        description = cachedPreview?.description,
                        imageUrl = null,
                        modifier = Modifier.heightIn(max = summaryLimit).verticalScroll(summaryScroll),
                    )
                    VideoProbeControls(
                        state = state,
                        pageState = session.pageState,
                        notice = session.notice,
                        modifier = Modifier.fillMaxWidth().heightIn(max = controlsLimit)
                            .verticalScroll(controlsScroll).padding(horizontal = 16.dp),
                        // 用户动作只改变内存会话，显隐不直接 loadUrl。
                        onShow = {
                            session.showWebPage()
                            logI("VideoProbe") { "查看提取网页 sessionId=${session.sessionId} mode=${session.mode}" }
                        },
                        onHide = {
                            session.hideWebPage()
                            logI("VideoProbe") { "收起提取网页 sessionId=${session.sessionId} mode=${session.mode}" }
                        },
                        onRetry = {
                            session.retry()
                            logI("VideoProbe") { "重新提取视频 sessionId=${session.sessionId} mode=${session.mode}" }
                        },
                    )
                    // 同一组合位置和 session key 保证展开/收起沿用已有原生网页；广告仅影响前景结果视口。
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        // 摘要和操作区已在外层扣除，此高度只分配给结果/网页和固定底栏。
                        val availableHeightPx = constraints.maxHeight
                        // 与图片及列表底栏沿用同一操作视口基线，空间不足时优先保留视频操作。
                        val minimumContentHeightPx = with(LocalDensity.current) { 96.dp.roundToPx() }
                        if (state is ProbeState.Probing) {
                            key(state.sessionId) {
                                SharedVideoProbeWebViewLayer(
                                    targetUrl = pageUrl,
                                    sessionId = state.sessionId,
                                    pageName = pageName,
                                    mode = state.mode,
                                    modifier = Modifier.matchParentSize(),
                                    // 辅助缓存与识别结果分离，最多等待已有的 1.5 秒预算。
                                    onSaveLinkPreview = { view -> videoExtractVm.saveWebViewLinkPreview(view, pageUrl) },
                                    // 旧网页的 post 回调仍需会话层拒绝，日志不展开候选。
                                    onVideoCandidate = { found ->
                                        if (session.accept(state.sessionId, found)) logI("VideoProbe") {
                                            "视频候选已接受 sessionId=${state.sessionId}"
                                        }
                                    },
                                    onLoadEvent = { event -> session.onLoadEvent(state.sessionId, event) },
                                    // 内容错误仅在当前文档仍提取时终结；日志不输出站点文案或资源信息。
                                    onPageUnavailable = { documentId ->
                                        if (session.onPageUnavailable(state.sessionId, documentId)) logW("VideoProbe") {
                                            "网页不可继续提取 sessionId=${state.sessionId} documentId=$documentId reasonCode=site_error_page"
                                        }
                                    },
                                )
                            }
                        }
                        if (!interactive) {
                            Column(Modifier.fillMaxSize()) {
                                // 结果占满广告以外的剩余高度：短内容留白靠底，长内容在广告上方滚动。
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(resultScroll),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (state is ProbeState.Success) {
                                        // 权限等待继续由页面抑制广告请求及呈现，不改变下载提交逻辑。
                                        Success(videoExtractVm, state.candidate) { permissionPending = it }
                                    }
                                }
                                // 唯一底栏在滚动区外；展开网页时随前景移除并释放，零高状态不预留空槽。
                                placement?.let { adPlacement ->
                                    MediaAdFooter(
                                        session = adSession,
                                        placement = adPlacement,
                                        availableHeightPx = availableHeightPx,
                                        minimumContentHeightPx = minimumContentHeightPx,
                                        blocked = permissionPending,
                                        canReveal = !permissionPending && !resultScroll.isScrollInProgress,
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
