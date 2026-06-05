package com.cla.clip.master.ui.page.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.logD
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.ui.navigation.BackupMediaRelocationRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.widget.SecondaryPageScaffold

private const val MEDIA_RELOCATION_NAV_DEBOUNCE_MS = 600L
private const val TAG = "BackupRestoreFlowPage"

/**
 * 备份恢复流程页。
 *
 * 页面承载读取、预览、恢复中、结果和失败状态；文件读写、WebDAV 下载和 Room 恢复仍由 ViewModel 编排。
 *
 * @param onCloseRestoreFlow 恢复结果页底部“完成”的闭环出口，只关闭恢复页和备份页，不固定跳转首页 Tab。
 */
@Composable
internal fun BackupRestoreFlowPage(
    onBack: () -> Unit,
    onCloseRestoreFlow: () -> Unit,
    onNavigate: (Route) -> Unit,
    magnetFeatures: Set<MagnetFeatureEntry> = emptySet(),
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
        magnetFeatures = magnetFeatures,
        modifier = modifier
    )
}

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
    magnetFeatures: Set<MagnetFeatureEntry>,
    modifier: Modifier = Modifier,
) {
    var showExitConfirm by remember(state.logCode) { mutableStateOf(false) }
    var showMediaRunningNotice by remember { mutableStateOf(false) }

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
        modifier = modifier.fillMaxSize(),
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BackupRestoreFlowContent(
                state = state,
                mediaRelocationEntryState = mediaRelocationEntryState,
                mediaRelocationSummary = mediaRelocationSummary,
                magnetFeatures = magnetFeatures
            )
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
