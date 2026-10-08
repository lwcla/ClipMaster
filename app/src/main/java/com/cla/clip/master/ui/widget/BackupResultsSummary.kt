package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cla.clip.master.R
import com.cla.clip.base.general.R as BaseR
import com.cla.clip.master.backup.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 两个独立结果区域，使用自然换行；只负责展示，生命周期由页面负责。 */
@Composable
internal fun BackupResultsSummary(state: BackupEntryState, modifier: Modifier = Modifier) {
    Column(modifier) {
        BackupResultRow(stringResource(R.string.host_backup_target_local), state.local)
        BackupResultRow(stringResource(R.string.host_backup_target_webdav), state.webDav)
    }
}

/** 单一目标结果文案，避免把时间或失败状态连成一条截断文本。 */
@Composable
private fun BackupResultRow(target: String, result: BackupResultPresentation) {
    /** 语义分类对应本地化资源；格式化仅在展示成功结果时发生。 */
    val text = when (result.kind) {
        BackupResultKind.Success -> stringResource(R.string.host_backup_result_success,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(result.completedAt)))
        BackupResultKind.Failed -> stringResource(R.string.host_backup_result_failed)
        BackupResultKind.Never -> stringResource(R.string.host_backup_result_never)
        BackupResultKind.LocalUnconfigured -> stringResource(R.string.host_backup_result_local_unconfigured)
        BackupResultKind.WebDavUnconfigured -> stringResource(R.string.host_backup_result_unconfigured)
        BackupResultKind.WaitingExecution -> stringResource(R.string.host_backup_result_waiting_execution)
        BackupResultKind.Generating -> stringResource(R.string.host_backup_result_generating)
        BackupResultKind.SavingLocal -> stringResource(R.string.host_backup_result_saving_local)
        BackupResultKind.ReadFailed -> stringResource(R.string.host_backup_result_read_failed)
        BackupResultKind.Loading -> stringResource(R.string.host_backup_result_loading)
        BackupResultKind.Uploading -> stringResource(R.string.host_backup_result_uploading)
    }
    Text(text = stringResource(R.string.host_backup_result_row, target, text), style = MaterialTheme.typography.bodyMedium,
        color = if (result.kind == BackupResultKind.Failed || result.kind == BackupResultKind.ReadFailed)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 备份页详情展示生成时间、等待阶段和可行动失败提示，不影响入口历史结论。 */
@Composable
internal fun BackupResultDetails(state: BackupEntryState, pending: List<BackupPendingPresentation>, modifier: Modifier = Modifier) {
    Column(modifier) {
        pending.forEach { current ->
            /** 展示快照生成时间，使用现有成功时间格式策略。 */
            val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(current.createdAt))
            Text(stringResource(R.string.host_backup_pending_created_at, createdAt), style = MaterialTheme.typography.bodyMedium)
            BackupResultRow(stringResource(if (current.target == com.cla.clip.base.general.backup.settings.BackupResultTarget.Local)
                R.string.host_backup_target_local else R.string.host_backup_target_webdav), BackupResultPresentation(current.kind))
        }
        listOf(R.string.host_backup_target_local to state.local, R.string.host_backup_target_webdav to state.webDav)
            .filter { it.second.kind == BackupResultKind.Failed }.forEach { (target, result) ->
                // 只映射固定 code，不向用户显示异常原文、地址或实现字段。
                /** 纯 mapper 决定错误语义，组件只选择资源；旧中断记录也使用明确提示。 */
                val message = when (BackupFailureMessageMapper.map(result.reasonCode)) {
                    BackupFailureMessage.Storage -> BaseR.string.base_general_backup_error_storage
                    BackupFailureMessage.Auth -> BaseR.string.base_general_backup_error_auth
                    BackupFailureMessage.Remote -> BaseR.string.base_general_backup_error_remote
                    BackupFailureMessage.Space -> BaseR.string.base_general_backup_error_insufficient_space
                    BackupFailureMessage.TempFile -> BaseR.string.base_general_backup_error_temp_file_unavailable
                    BackupFailureMessage.Interrupted -> R.string.host_backup_error_upload_interrupted
                    BackupFailureMessage.Timeout -> R.string.host_backup_error_upload_timeout
                    BackupFailureMessage.Quota -> R.string.host_backup_error_upload_quota
                    BackupFailureMessage.Network -> R.string.host_backup_error_upload_network_interrupted
                    BackupFailureMessage.DeviceState -> R.string.host_backup_error_upload_device_interrupted
                    BackupFailureMessage.Cancelled -> R.string.host_backup_error_upload_cancelled
                    BackupFailureMessage.Schedule -> R.string.host_backup_error_schedule
                    BackupFailureMessage.Handoff -> R.string.host_backup_error_handoff
                    BackupFailureMessage.Unknown -> BaseR.string.base_general_backup_error_unknown
                }
                Text(stringResource(R.string.host_backup_result_row, stringResource(target), stringResource(message)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
    }
}

/** 深色等待与读取错误，配合大字体 Preview 检查独立结果区域。 */
@Preview(widthDp = 220, fontScale = 1.6f, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackupResultsWaitingPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        BackupResultsSummary(BackupEntryState(BackupResultPresentation(BackupResultKind.ReadFailed),
            BackupResultPresentation(BackupResultKind.WaitingExecution)))
    }
}

/** 窄屏及大字体覆盖两条结果自然换行，深色主题颜色来自 Material。 */
@Preview(widthDp = 220, fontScale = 1.6f)
@Composable
private fun BackupResultsSummaryPreview() {
    MaterialTheme {
        BackupResultsSummary(BackupEntryState(BackupResultPresentation(BackupResultKind.Success, 100L),
            BackupResultPresentation(BackupResultKind.Failed)))
    }
}
