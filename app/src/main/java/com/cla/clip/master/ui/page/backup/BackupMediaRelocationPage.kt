package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.master.R as HostR
import com.cla.clip.base.general.utils.logD
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.PageAdSlot
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.rememberTopAdScrollLayoutCoordinator
import com.cla.clip.master.media.MediaRelocationPreparation
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.ad.AdHeaderLayout

private const val TAG = "BackupMediaRelocationPage"

/**
 * 恢复本地媒体关联独立页。
 *
 * 页面按标题说明、广告、关联详情编排当前任务；扫描副作用由 `BackupMediaRelocationVm` 编排。
 *
 * @param onCloseRestoreFlow 正向终态点击“完成”时关闭备份页、恢复页和媒体关联页，保留用户进入备份前的导航位置。
 */
@Composable
internal fun BackupMediaRelocationPage(
    restoreTaskId: String,
    flowSession: BackupFlowSession,
    onBack: () -> Unit,
    onInvalidSession: () -> Unit,
    onCloseRestoreFlow: () -> Unit,
    modifier: Modifier = Modifier,
    relocationVm: BackupMediaRelocationVm = hiltViewModel(),
) {
    // 业务状态沿用生命周期感知订阅，广告仅接收由状态派生的资格布尔值。
    val state by relocationVm.uiState.collectAsStateWithLifecycle()
    // 运行中返回提示只阻止广告交互，不改变媒体关联任务。
    var showRunningNotice by remember { mutableStateOf(false) }
    // 系统权限选择器期间保留已加载素材，但禁止新请求、展开和点击。
    var permissionRequestPending by remember(restoreTaskId) { mutableStateOf(false) }
    // TaskId 只隔离页面内存会话，不进入广告请求或广告日志。
    val adSession = rememberPageAdSession(restoreTaskId)
    // 广告与正文一起滚动，共用锚点补偿和按压保护。
    val contentScroll = rememberScrollState()
    // 标题说明及其底部间距位于广告之前，实测后交给锚点协调器保护。
    var contentBeforeAdHeight by remember(contentScroll) { mutableIntStateOf(0) }
    // 确认与结果阶段展示真实内容，纯扫描/等待阶段不承载广告。
    val adEligible = state.isAdEligible
    // 普通遮挡和业务资格分开：遮挡结束可以继续使用有效的原素材。
    val adBlocked = showRunningNotice || permissionRequestPending
    // 同步最新许可，SDK 回调不能趁弹窗或业务阶段变化插入广告。
    SideEffect { adSession.suppressed = adBlocked || !adEligible }
    // 广告总高度包含条件间隔，补偿仅作用于其下方的关联详情。
    val adHeaderModifier = rememberTopAdScrollLayoutCoordinator(adSession, contentScroll, adBlocked, contentBeforeAdHeight)
    // 权限返回先解除临时门控，资格再由 ViewModel 的复查状态控制。
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            permissionRequestPending = false
            relocationVm.onPermissionResult(permissions)
        }
    )

    /** 只在前台验证当前图结果；重组和权限弹窗不会重新预估或扫描。 */
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(relocationVm, flowSession, restoreTaskId, lifecycleOwner) {
        if (relocationVm.bindSession(flowSession)) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (flowSession.matchesRestoreTask(restoreTaskId)) {
                    relocationVm.start(restoreTaskId)
                } else {
                    onInvalidSession()
                }
            }
        }
    }

    /** 处理顶部返回和系统返回；除运行态拦截外始终只回上一层，方便用户回恢复页查看结果。 */
    fun requestBack() {
        /** 当前 UI 状态对应的稳定日志 code，用于排查返回行为是否命中预期分支。 */
        val uiStateLogCode = state.backLogCode
        /** 返回请求日志先在普通代码路径组装，避免日志惰性求值影响调试断点。 */
        val backRequestLog = "媒体关联返回请求 restoreTaskId=$restoreTaskId uiState=$uiStateLogCode " +
            "backTarget=restore_flow"
        logD(TAG) { backRequestLog }
        // 同步检查会话事实：扫描刚启动但 UI 未重组时也拦截，关闭后的旧回调直接忽略。
        if (!flowSession.canLeaveMedia(restoreTaskId)) {
            showRunningNotice = flowSession.matchesRestoreTask(restoreTaskId)
            return
        }
        onBack()
    }

    /** 处理底部“完成”；正向终态关闭整条备份恢复链路，其他终态仍回恢复页保留重试入口。 */
    fun requestDone() {
        /** 当前 UI 状态对应的稳定日志 code，用于区分完成按钮和普通返回路径。 */
        val uiStateLogCode = state.backLogCode
        /** 正向终态完成需要关闭备份恢复链路；优先读取 ViewModel 终态标记，再用 UI state 兜底。 */
        val shouldCloseRestoreFlow = relocationVm.shouldCloseRestoreFlowOnDone || state.shouldCloseRestoreFlowAfterDone
        /** 完成请求日志先在普通代码路径组装，记录最终落点而不输出敏感媒体信息。 */
        val doneRequestLog = "媒体关联完成请求 restoreTaskId=$restoreTaskId uiState=$uiStateLogCode " +
            "shouldCloseRestoreFlow=$shouldCloseRestoreFlow"
        logD(TAG) { doneRequestLog }
        // 完成与普通返回共用同步门禁，不能以捕获的旧 Ready/Result 状态绕过 Running。
        if (!flowSession.canLeaveMedia(restoreTaskId)) {
            showRunningNotice = flowSession.matchesRestoreTask(restoreTaskId)
            return
        }
        if (shouldCloseRestoreFlow) {
            logD(TAG) { "媒体关联成功终态完成并关闭恢复链路 restoreTaskId=$restoreTaskId reasonCode=success_done_close_restore_flow" }
            onCloseRestoreFlow()
        } else {
            onBack()
        }
    }

    BackHandler(enabled = true) {
        requestBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize().adInteractionGuard(adSession),
        topBar = {
            TitleBar(
                title = stringResource(R.string.base_general_backup_media_relocation_title),
                onBack = { requestBack() }
            )
        },
        bottomBar = {
            BackupMediaRelocationActions(
                state = state,
                onBack = { requestBack() },
                onDone = { requestDone() },
                onRestart = relocationVm::restart,
                onRequestMediaPermission = { preparation ->
                    // 系统界面弹出前同步关闭广告交互，避免等待生命周期事件的窗口。
                    permissionRequestPending = true
                    mediaPermissionLauncher.launch(preparation.requiredPermissions.toTypedArray())
                },
                onStartScan = relocationVm::startScan
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
            // 标题和用途说明在广告之前；前置间隔属于原有正文结构，空广告时不会重复留白。
            Column(
                modifier = Modifier
                    .onSizeChanged { size -> contentBeforeAdHeight = size.height }
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.base_general_backup_media_relocation_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.base_general_backup_media_relocation_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 与正文共用左右 20dp 边界，空槽零高，已展示广告下方留 16dp。
            AdHeaderLayout(modifier = adHeaderModifier) {
                PageAdSlot(
                    session = adSession,
                    placement = AdPlacement.BackupMediaRelocationNative,
                    eligible = adEligible,
                    requestEnabled = adEligible && !adBlocked,
                    canReveal = contentScroll.value == 0 && !contentScroll.isScrollInProgress && !adBlocked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 正文原有段落间隔保持独立，不能让外层间距为空广告制造占位。
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BackupMediaRelocationBody(state)
            }
        }
    }

    if (showRunningNotice) {
        AlertDialog(
            onDismissRequest = { showRunningNotice = false },
            title = { Text(text = stringResource(R.string.base_general_backup_media_relocation_title)) },
            text = { Text(text = stringResource(R.string.base_general_backup_media_relocation_running_back_hint)) },
            confirmButton = {
                TextButton(onClick = { showRunningNotice = false }) {
                    Text(text = stringResource(R.string.base_general_backup_media_relocation_known))
                }
            }
        )
    }
}

/** 渲染广告之后的关联详情；标题说明由页面先行展示，所有业务分支保持原样。 */
@Composable
private fun BackupMediaRelocationBody(state: MediaRelocationUiState) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    when (state) {
        MediaRelocationUiState.Idle,
        MediaRelocationUiState.Estimating -> ProgressRow(
            text = stringResource(R.string.base_general_backup_media_relocation_estimating)
        )
        is MediaRelocationUiState.PermissionChecking -> {
            MediaRelocationEstimateRows(state.preparation)
            ProgressRow(text = stringResource(R.string.base_general_backup_media_relocation_permission_checking))
        }
        is MediaRelocationUiState.NoWork -> {
            MediaRelocationSummaryText(
                MediaRelocationSummary(
                    type = MediaRelocationSummaryType.NoWork,
                    report = state.preparation.toExistingReadableReport()
                )
            )
            MediaRelocationResultRows(state.preparation.toExistingReadableReport())
        }
        is MediaRelocationUiState.ReadyToConfirm -> {
            MediaRelocationEstimateRows(state.preparation)
        }
        is MediaRelocationUiState.PermissionRequired -> {
            MediaRelocationEstimateRows(state.preparation)
            Text(
                text = stringResource(R.string.base_general_backup_media_relocation_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.report?.let {
                MediaRelocationSummaryText(MediaRelocationSummary(MediaRelocationSummaryType.PermissionDenied, it))
                MediaRelocationResultRows(it)
            }
        }
        is MediaRelocationUiState.Running -> {
            MediaRelocationProgressRows(state.progress) { ProgressRow(it) }
        }
        is MediaRelocationUiState.Result -> {
            MediaRelocationSummaryText(MediaRelocationSummary(MediaRelocationSummaryType.Completed, state.report))
            MediaRelocationResultRows(state.report)

        }
        is MediaRelocationUiState.Error -> Text(
            text = state.message.ifBlank { stringResource(R.string.base_general_backup_media_relocation_error) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun BackupMediaRelocationActions(
    state: MediaRelocationUiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onRestart: () -> Unit,
    onRequestMediaPermission: (MediaRelocationPreparation) -> Unit,
    onStartScan: (MediaRelocationPreparation) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            MediaRelocationUiState.Idle,
            MediaRelocationUiState.Estimating,
            is MediaRelocationUiState.PermissionChecking,
            is MediaRelocationUiState.Running -> Unit
            is MediaRelocationUiState.ReadyToConfirm -> {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.base_general_cancel))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = { onStartScan(state.preparation) }) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_confirm))
                }
            }
            is MediaRelocationUiState.PermissionRequired -> {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.base_general_cancel))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = { onRequestMediaPermission(state.preparation) }) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_request_permission))
                }
            }
            is MediaRelocationUiState.NoWork,
            is MediaRelocationUiState.Result -> {
                TextButton(onClick = onRestart) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_restart))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onDone) {
                    Text(stringResource(R.string.base_general_backup_flow_done))
                }
            }
            is MediaRelocationUiState.Error -> {
                TextButton(onClick = onRestart) {
                    Text(stringResource(R.string.base_general_backup_media_relocation_restart))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onBack) {
                    Text(stringResource(R.string.base_general_backup_flow_done))
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
