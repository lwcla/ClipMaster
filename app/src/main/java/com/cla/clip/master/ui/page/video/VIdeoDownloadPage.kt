package com.cla.clip.master.ui.page.video

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ui.widget.media.MediaAdFooter
import com.cla.clip.master.ui.widget.media.MediaSourceSummary
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.ui.theme.ClipMaterTheme
import com.cla.clip.master.ui.widget.PageBackground
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import kotlinx.coroutines.launch

/**
 * 视频下载页面。
 *
 * 页面进入后立即根据 taskId 启动或恢复下载状态观察，下载执行由 Worker 在后台完成。
 * 返回键保持普通返回行为，不取消下载任务，避免用户离开页面后任务被意外终止。
 * 摘要与阶段状态在剩余视口内滚动，下载、合并及成功阶段的广告共用固定底栏布局。
 */
@Composable
fun VideoDownloadPage(
    downloadVm: VideoDownloadVm = hiltViewModel(),
    taskId: Long,
    onBack: () -> Unit
) {
    // 页面状态由既有生命周期感知订阅提供，Worker 不受页面可见性影响。
    val state = downloadVm.downloadState.collectAsStateWithLifecycle().value
    // 元数据与上面的状态共享 Room 观察，不重复读取任务或下载媒体。
    val task = downloadVm.taskData.collectAsStateWithLifecycle().value
    // 下载和合并共享广告位置；成功阶段重新建立独立结果会话。
    val placement = videoDownloadAdPlacement(state, task != null)
    // 会话身份不含百分比，因此 Room 高频进度不会重载广告。
    val adSession = rememberPageAdSession(taskId to placement, enabled = placement != null)
    // 任务信息与操作独立滚动，底栏保持靠底；滚动期间仍不呈现新的广告素材。
    val contentScroll = rememberScrollState()
    SideEffect { adSession.canMutateLayout = !contentScroll.isScrollInProgress }

    // 拦截系统返回键，保持和标题栏返回一致的导航行为。
    BackHandler {
        onBack()
    }

    LaunchedEffect(taskId, downloadVm.sessionId) {
        // sessionId 只在首次进入和点击重试时变化，避免重组重复入队下载 Worker。
        downloadVm.startDownload(downloadVm.sessionId, taskId)
    }

    SecondaryPageScaffold(
        modifier = Modifier.adInteractionGuard(adSession),
        title = stringResource(R.string.base_general_video_download),
        onBack = onBack,
    ) { paddingValues ->
        PageBackground(
            // 骨架已消费普通导航栏；仅补未消费的底部必需手势区，兼容三星零高导航栏。
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom),
            ),
            contentPadding = paddingValues,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 页面标题与系统安全区已被扣除，内容和广告仅共享此有界高度。
                val availableHeightPx = constraints.maxHeight
                // 沿用共享底栏的 96dp 业务视口基线，广告放不下时优先保证状态与操作可达。
                val minimumContentHeightPx = with(LocalDensity.current) { 96.dp.roundToPx() }
                Column(Modifier.fillMaxSize()) {
                    // 短内容由剩余空间推开广告；长内容只在底栏上方滚动，不遮挡进度或播放入口。
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (task != null) {
                            MediaSourceSummary(
                                title = task.fileName,
                                source = Uri.parse(task.referer ?: task.videoUrl).host,
                                description = Uri.parse(task.videoUrl).lastPathSegment,
                                imageUrl = null,
                            )
                        }
                        when (state) {
                            is VideoDownloadState.Idle -> {
                                Text(
                                    text = stringResource(R.string.base_general_preparing_download),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            is VideoDownloadState.Downloading -> {
                                VideoDownloadProgressContent(state)
                            }

                            is VideoDownloadState.Merging -> {
                                VideoMergeProgressContent(state)
                            }

                            is VideoDownloadState.Success -> {
                                Success(state)
                            }

                            is VideoDownloadState.Failed -> {
                                VideoDownloadFailureContent(
                                    state,
                                    taskMissing = task == null && state.errorMsg != "download_read_failed",
                                    retry = {
                                        // 通过改变 sessionId 触发 ViewModel 开启新一轮下载。
                                        downloadVm.sessionId++
                                    }
                                )
                            }
                        }
                    }
                    // 三个有效下载阶段共用唯一固定槽；阶段资格与会话身份沿用原策略。
                    placement?.let { adPlacement ->
                        MediaAdFooter(
                            session = adSession,
                            placement = adPlacement,
                            availableHeightPx = availableHeightPx,
                            minimumContentHeightPx = minimumContentHeightPx,
                            blocked = false,
                            canReveal = !contentScroll.isScrollInProgress,
                            // 与摘要对齐，替代底栏默认留白并共同继承页面外层边距。
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 下载失败状态，点击整行触发重试。 */
@Composable
internal fun VideoDownloadFailureContent(
    // 失败状态沿用现有原因映射，不在展示层改变错误分类。
    state: VideoDownloadState.Failed,
    // 原页面负责重试业务，展示层只透传点击。
    retry: () -> Unit,
    // 布局修饰符位于首个可选参数，遵循共享 Compose 接口约定。
    modifier: Modifier = Modifier,
    // 已失效任务只显示原因，保留原禁用重试契约。
    taskMissing: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable(enabled = !taskMissing, onClick = retry)
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
            text = stringResource(videoDownloadFailureMessage(state.errorMsg, taskMissing)),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

/** 合成错误预览仅展示既有重试入口。 */
@Preview(showBackground = true)
@Composable
private fun FailedPreview() {
    ClipMaterTheme {
        VideoDownloadFailureContent(
            state = VideoDownloadState.Failed(errorMsg = "下载失败，点击重试"),
            retry = {}
        )
    }
}

/** 下载成功状态，点击后尝试使用系统播放器打开保存的视频。 */
@Composable
private fun Success(state: VideoDownloadState.Success) {
    // 原页面上下文与协程仅用于播放器启动和失败提示，不传入纯展示组件。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    VideoDownloadSuccessContent(onOpen = {
            // 只在用户点击后读取当前结果 URI；空路径仍直接忽略。
            val uri = state.savePath?.let(Uri::parse) ?: return@VideoDownloadSuccessContent
            // 外部播放器授权保持原有临时读取边界，不交由纯展示组件处理。
            val intent = Intent(Intent.ACTION_VIEW).apply {
                // 下载结果可能是内容 URI，授予临时读取权限给外部播放器。
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(intent)
            }.onFailure {
                scope.launch { context.toast(R.string.base_general_there_is_no_available_application_to_open_this_video) }
            }
    })
}

@Preview(showBackground = true)
@Composable
private fun SuccessPreview() {
    ClipMaterTheme {
        Success(VideoDownloadState.Success("download/path/video.mp4"))
    }
}

/** 普通视频文件下载进度状态。 */
@Composable
internal fun VideoDownloadProgressContent(state: VideoDownloadState.Downloading, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = 40.dp)
    ) {
        LinearProgressIndicator(
            progress = { state.progress.toFloat() / 100 },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.base_general_download_progress, state.progress),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** 固定合成百分比不驱动下载任务。 */
@Preview(showBackground = true)
@Composable
private fun DownloadingPreview() {
    ClipMaterTheme {
        VideoDownloadProgressContent(VideoDownloadState.Downloading(progress = 66))
    }
}

/** M3U8 分片下载完成后的合并进度状态。 */
@Composable
internal fun VideoMergeProgressContent(state: VideoDownloadState.Merging, modifier: Modifier = Modifier) {

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = 40.dp)
    ) {
        LinearProgressIndicator(
            progress = { state.progress.toFloat() / 100 },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.base_general_merge_progress, state.progress),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** 固定合成百分比仅展示合并阶段。 */
@Preview(showBackground = true)
@Composable
private fun MergingPreview() {
    ClipMaterTheme {
        VideoMergeProgressContent(VideoDownloadState.Merging(progress = 66))
    }
}

/** 成功状态只展示播放入口；系统播放器启动留在页面适配层，避免布局测试产生外部副作用。 */
@Composable
internal fun VideoDownloadSuccessContent(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
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
            modifier = Modifier.padding(start = 0.dp, top = 0.dp, end = 12.dp, bottom = 0.dp)
        )
    }
}
