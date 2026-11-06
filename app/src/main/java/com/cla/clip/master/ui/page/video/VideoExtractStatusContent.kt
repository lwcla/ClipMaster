package com.cla.clip.master.ui.page.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.widget.RequestStoragePermission
import com.cla.clip.master.R
import com.cla.clip.master.entity.VideoCandidate
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.InlineErrorState
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.InlineSuccessState
import com.cla.clip.master.ui.widget.ProbeWebFailure
import com.cla.clip.master.ui.widget.ProbeWebFailureKind
import com.cla.clip.base.general.R as GeneralR

/** 视频会话专用状态与操作适配；只接收内存状态和回调，不持有网页或发起导航。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoProbeControls(
    state: ProbeState,
    pageState: VideoProbePageState,
    notice: VideoProbeNotice?,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 可见网页只属于正在运行的交互会话，成功后不再显示查看入口。
    val interactive = (state as? ProbeState.Probing)?.mode == VideoProbeMode.Interactive
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state) {
            ProbeState.Idle -> Text(stringResource(GeneralR.string.base_general_extract_the_video_address))
            is ProbeState.Failed -> InlineErrorState(videoProbeMessageText(videoProbeFailureMessage(state.reason)))
            is ProbeState.Success -> Unit
            is ProbeState.Probing -> when (pageState) {
                is VideoProbePageState.Loading -> InlineLoadingState(stringResource(R.string.host_video_probe_loading, pageState.percent))
                is VideoProbePageState.Loaded -> Text(stringResource(R.string.host_video_probe_waiting))
                is VideoProbePageState.Error -> InlineErrorState(
                    videoProbeMessageText(videoProbeFailureMessage(VideoProbeFailure.PageLoad(pageState.failure))),
                )
            }
        }
        if (state !is ProbeState.Success) {
            // 操作在窄屏/大字体下自动换行，不挤占或覆盖网页点击区域。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (interactive) onHide else onShow) {
                    Text(stringResource(when {
                        interactive -> R.string.host_video_probe_hide_page
                        state is ProbeState.Failed -> R.string.host_video_probe_view_retry
                        else -> R.string.host_video_probe_show_process
                    }))
                }
                if (interactive || state is ProbeState.Failed) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.host_video_probe_retry)) }
                }
            }
        }
        if (interactive) {
            Text(stringResource(R.string.host_video_probe_help), style = MaterialTheme.typography.bodySmall)
        }
        if (notice != null && state is ProbeState.Probing) {
            Text(stringResource(when (notice) {
                VideoProbeNotice.LongRunning -> R.string.host_video_probe_long_running
                VideoProbeNotice.Reloaded -> R.string.host_video_probe_reloaded
                VideoProbeNotice.ExternalAppBlocked -> R.string.host_video_probe_external
            }), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 仅对资源文案做本地化和数值占位符替换，不展示网页原始错误文本。 */
@Composable
private fun videoProbeMessageText(message: VideoProbeMessage): String =
    if (message.number == null) stringResource(message.resourceId) else stringResource(message.resourceId, message.number)

/**
 * 视频地址识别成功后的权限与下载适配。
 * 用户确认下载才创建任务，权限弹层期间禁用重复提交，并将等待状态回传给页面广告门控。
 */
@Composable
internal fun Success(
    videoExtractVm: VideoExtractVm,
    candidate: VideoCandidate,
    onPermissionPending: (Boolean) -> Unit = {},
) {
    // 时间戳区分同一候选的多次权限申请，不参与候选业务身份。
    var pendingCandidate by remember { mutableStateOf<Pair<Long, VideoCandidate>?>(null) }
    // 权限等待属于当前组件，退出必须解除父页面等待标记。
    SideEffect { onPermissionPending(pendingCandidate != null) }
    DisposableEffect(Unit) { onDispose { onPermissionPending(false) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        pendingCandidate?.let { pending ->
            key(pending.first) {
                RequestStoragePermission(
                    // 拒绝或关闭申请后允许用户再次操作。
                    onFinished = { granted -> if (!granted) pendingCandidate = null },
                    // 申请成功后显式提交，页面本身不在重组时自动创建下载。
                    next = {
                        pendingCandidate = null
                        videoExtractVm.startDownloadAndGo(pending.second)
                    },
                )
            }
        }
        VideoExtractSuccessContent(
            isSubmitting = videoExtractVm.isSubmitting,
            submissionFailed = videoExtractVm.submissionFailed,
            permissionPending = pendingCandidate != null,
            // 权限请求仍由原适配层发起，纯展示入口只上抛用户意图。
            onDownload = { pendingCandidate = System.currentTimeMillis() to candidate },
        )
    }
}

/** 无网页/无 ViewModel 的窄屏预览，覆盖可操作状态和长错误提示换行。 */
@Preview(showBackground = true, widthDp = 320, fontScale = 1.3f)
@Composable
private fun VideoProbeControlsPreview() {
    ClipMaterTheme {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VideoProbeControls(ProbeState.Probing(1, VideoProbeMode.Interactive), VideoProbePageState.Loaded(1),
                VideoProbeNotice.LongRunning, onShow = {}, onHide = {}, onRetry = {})
            VideoProbeControls(ProbeState.Failed(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Http, 403))),
                VideoProbePageState.Loading(), null, onShow = {}, onHide = {}, onRetry = {})
        }
    }
}

/** 成功状态的纯展示区域；权限申请和提交任务由调用方负责，测试可使用空回调。 */
@Composable
internal fun VideoExtractSuccessContent(
    isSubmitting: Boolean,
    submissionFailed: Boolean,
    permissionPending: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        InlineSuccessState(stringResource(GeneralR.string.base_general_extract_the_video_address_success))
        Button(
            // 同一时刻只启动一次权限/提交流程。
            onClick = onDownload,
            enabled = !permissionPending && !isSubmitting,
        ) {
            Text(stringResource(if (isSubmitting) GeneralR.string.base_general_preparing_download
                else GeneralR.string.base_general_to_download))
        }
        if (submissionFailed) {
            InlineErrorState(stringResource(GeneralR.string.base_general_failed_to_create_the_download_task))
        }
    }
}
