package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.PageAdSlot
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ad.rememberTopAdScrollLayoutCoordinator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.feature.torrent.search.api.TorrentSearchFeatureEntry
import com.cla.clip.master.ui.navigation.BackupMediaRelocationRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.ad.AdHeaderLayout

private const val MEDIA_RELOCATION_NAV_DEBOUNCE_MS = 600L
private const val TAG = "BackupRestoreFlowPage"

/**
 * 备份恢复流程页。
 *
 * 页面承载读取、预览、恢复中、结果和失败状态；文件读写、WebDAV 下载和 Room 恢复仍由 ViewModel 编排。
 *
 * @param onCloseRestoreFlow 恢复结果页底部“完成”的闭环出口，只关闭恢复页和备份页，不固定跳转首页 Tab。
 * @param torrentSearchFeatures 可选功能的备份展示契约；关闭实现模块时为空，不改变宿主恢复流程。
 */
@Composable
internal fun BackupRestoreFlowPage(
    onBack: () -> Unit,
    onCloseRestoreFlow: () -> Unit,
    onNavigate: (Route) -> Unit,
    torrentSearchFeatures: Set<TorrentSearchFeatureEntry> = emptySet(),
    modifier: Modifier = Modifier,
    restoreVm: BackupRestoreVm = hiltViewModel(),
) {
    val state by restoreVm.uiState.collectAsStateWithLifecycle()
    val restoreFlow = state.restoreFlow
    var lastMediaRelocationNavigateAt by remember { mutableLongStateOf(0L) }

    if (restoreFlow is BackupRestoreFlowState.Hidden) {
        LaunchedEffect(restoreFlow) {
            onBack()
        }
        return
    }

    BackupRestoreFlowScaffold(
        state = restoreFlow,
        mediaRelocationEntryState = state.mediaRelocationEntryState,
        mediaRelocationSummary = state.lastTerminalMediaRelocationSummary,
        onBack = {
            restoreVm.dismissRestoreFlow()
            onBack()
        },
        onDone = {
            restoreVm.dismissRestoreFlow()
            onCloseRestoreFlow()
        },
        onForceBack = {
            restoreVm.forceCloseRestoreFlow()
            onBack()
        },
        onRestore = restoreVm::restoreSelectedBackup,
        onOpenMediaRelocation = {
            val result = restoreFlow as? BackupRestoreFlowState.Result ?: return@BackupRestoreFlowScaffold
            val now = System.currentTimeMillis()
            if (now - lastMediaRelocationNavigateAt < MEDIA_RELOCATION_NAV_DEBOUNCE_MS) {
                logD(TAG) { "媒体关联页导航已防抖 restoreTaskId=${result.taskId} reasonCode=nav_debounced" }
                return@BackupRestoreFlowScaffold
            }
            lastMediaRelocationNavigateAt = now
            onNavigate(BackupMediaRelocationRoute(result.taskId))
            logD(TAG) { "打开媒体关联页 restoreTaskId=${result.taskId}" }
        },
        torrentSearchFeatures = torrentSearchFeatures,
        modifier = modifier
    )
}

/** 连接恢复页外壳与内容区，并把可选功能展示契约原样透传给纯渲染内容。 */
@Composable
private fun BackupRestoreFlowScaffold(
    state: BackupRestoreFlowState,
    mediaRelocationEntryState: MediaRelocationEntryState,
    mediaRelocationSummary: MediaRelocationSummary?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onForceBack: () -> Unit,
    onRestore: () -> Unit,
    onOpenMediaRelocation: () -> Unit,
    torrentSearchFeatures: Set<TorrentSearchFeatureEntry>,
    modifier: Modifier = Modifier,
) {
    // 确认弹窗按流程阶段重置；显示时暂停广告请求和尺寸变化。
    var showExitConfirm by remember(state.logCode) { mutableStateOf(false) }
    // 媒体关联提醒独立于恢复报告，只用于当前页面的交互抑制。
    var showMediaRunningNotice by remember { mutableStateOf(false) }

    // 同一恢复任务跨预览、执行和结果共用会话，不因阶段变化重复加载。
    val adTaskKey = when (state) {
        is BackupRestoreFlowState.Reading -> state.taskId
        is BackupRestoreFlowState.Preview -> state.taskId
        is BackupRestoreFlowState.Restoring -> state.taskId
        is BackupRestoreFlowState.Result -> state.taskId
        is BackupRestoreFlowState.Error -> state.taskId
        BackupRestoreFlowState.Hidden -> null
    }
    // 摘要、广告和详情共同滚动，工具栏与底部操作均不被广告覆盖。
    val contentScroll = rememberScrollState()
    // 广告前的状态摘要及间距实测高度，用于保护尚在阅读摘要的滚动位置。
    var contentBeforeAdHeight by remember(contentScroll) { mutableIntStateOf(0) }
    // 读取、纯恢复进度及失败页没有足够内容；预览和实际结果才允许展示。
    val eligible = state is BackupRestoreFlowState.Preview || state is BackupRestoreFlowState.Result
    // 弹窗不展示广告；按下由整个 Scaffold 的观察器统一守卫，包括底部恢复按钮。
    val blocked = showExitConfirm || showMediaRunningNotice
    // TaskId 仅作为本地 remember 身份，宿主只向 SDK 发送随机 nonce。
    val adSession = rememberPageAdSession(adTaskKey)
    SideEffect { adSession.suppressed = blocked || !eligible }
    // 广告尺寸变化只补偿其下方正文，固定的状态摘要不跟着广告收起而移动。
    val adHeaderModifier = rememberTopAdScrollLayoutCoordinator(adSession, contentScroll, blocked, contentBeforeAdHeight)

    /** 处理顶部返回和系统返回；结果态只回备份页，读取/恢复中按原规则二次确认。 */
    fun requestBack() {
        if (mediaRelocationEntryState.isRunning) {
            showMediaRunningNotice = true
        } else if (state.requiresExitConfirm) {
            showExitConfirm = true
        } else {
            onBack()
        }
    }

    /** 处理底部“完成”；结果态关闭恢复页和备份页，运行中的媒体关联仍拦截。 */
    fun requestDone() {
        /** 是否存在仍在运行的媒体关联任务，用于阻止恢复链路提前关闭。 */
        val mediaRelocationRunning = mediaRelocationEntryState.isRunning
        if (mediaRelocationRunning) {
            showMediaRunningNotice = true
        } else {
            logD(TAG) {
                "恢复流程完成并关闭备份恢复链路 state=${state.logCode} " +
                    "mediaRelocationRunning=$mediaRelocationRunning " +
                    "reasonCode=restore_flow_done_close_stack"
            }
            onDone()
        }
    }

    BackHandler(enabled = true) {
        requestBack()
    }

    SecondaryPageScaffold(
        modifier = modifier.fillMaxSize().adInteractionGuard(adSession),
        title = stringResource(R.string.base_general_backup_restore_flow_title),
        onBack = { requestBack() },
        bottomBar = {
            BackupRestoreFlowActions(
                state = state,
                mediaRelocationEntryState = mediaRelocationEntryState,
                onBack = { requestBack() },
                onDone = { requestDone() },
                onRestore = onRestore,
                onOpenMediaRelocation = onOpenMediaRelocation
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(contentScroll)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // 先展示状态和来源摘要；下方 16dp 是业务区块间距，空广告时仍保留这一处间隔。
            BackupRestoreFlowHeader(
                state = state,
                modifier = Modifier
                    .onSizeChanged { size -> contentBeforeAdHeight = size.height }
                    .padding(bottom = 16.dp),
            )
            // 头部测量包括实际广告和 16dp 正文分隔；无填充或禁用来源仍保持零高。
            AdHeaderLayout(modifier = adHeaderModifier) {
                PageAdSlot(
                    session = adSession,
                    placement = AdPlacement.BackupRestoreNative,
                    eligible = eligible,
                    requestEnabled = eligible && !blocked,
                    canReveal = contentScroll.value == 0 && !contentScroll.isScrollInProgress && !blocked,
                    // 填满已有左右 20dp 留白的正文容器，与恢复摘要/分隔线共用边界。
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BackupRestoreFlowContent(
                    state = state,
                    mediaRelocationEntryState = mediaRelocationEntryState,
                    mediaRelocationSummary = mediaRelocationSummary,
                    torrentSearchFeatures = torrentSearchFeatures,
                )
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(text = stringResource(R.string.base_general_backup_restore_exit_title)) },
            text = {
                Text(
                    text = if (state is BackupRestoreFlowState.Restoring) {
                        stringResource(R.string.base_general_backup_restore_exit_restoring_message)
                    } else {
                        stringResource(R.string.base_general_backup_restore_exit_reading_message)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        onForceBack()
                    }
                ) {
                    Text(text = stringResource(R.string.base_general_backup_restore_exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(text = stringResource(R.string.base_general_cancel))
                }
            }
        )
    }

    if (showMediaRunningNotice) {
        AlertDialog(
            onDismissRequest = { showMediaRunningNotice = false },
            title = { Text(text = stringResource(R.string.base_general_backup_media_relocation_title)) },
            text = { Text(text = stringResource(R.string.base_general_backup_media_relocation_running_back_hint)) },
            confirmButton = {
                TextButton(onClick = { showMediaRunningNotice = false }) {
                    Text(text = stringResource(R.string.base_general_backup_media_relocation_known))
                }
            }
        )
    }
}

private val BackupRestoreFlowState.requiresExitConfirm: Boolean
    get() = this is BackupRestoreFlowState.Reading || this is BackupRestoreFlowState.Restoring
