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

/** 单一目标结果直接说明失败原因，纯映射和资源选择共享，窄屏自然换行不截断。 */
@Composable
private fun BackupResultRow(target: String, result: BackupResultPresentation) {
    /** 语义分类对应本地化资源；格式化仅在展示成功结果时发生。 */
    val text = when (result.kind) {
        BackupResultKind.Success -> stringResource(R.string.host_backup_result_success,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(result.completedAt)))
        BackupResultKind.Failed -> stringResource(R.string.host_backup_result_failed_reason, BackupFailureReasonText(result.reasonCode))
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

/** 备份页只补充当前等待/运行详情；历史失败已在共享摘要展示，避免重复说明。 */
@Composable
internal fun BackupResultDetails(pending: List<BackupPendingPresentation>, modifier: Modifier = Modifier) {
    Column(modifier) {
        pending.forEach { current ->
            /** 展示快照生成时间，使用现有成功时间格式策略。 */
            val createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(current.createdAt))
            Text(stringResource(R.string.host_backup_pending_created_at, createdAt), style = MaterialTheme.typography.bodyMedium)
            BackupResultRow(stringResource(if (current.target == com.cla.clip.base.general.backup.settings.BackupResultTarget.Local)
                R.string.host_backup_target_local else R.string.host_backup_target_webdav), BackupResultPresentation(current.kind))
        }
    }
}

/** 共享摘要的本地化适配；只读固定原因码，既不访问网络也不展示异常正文。 */
@Composable
private fun BackupFailureReasonText(reasonCode: String): String {
    /** 经过纯校验的状态，用于安全格式化；非法格式由 mapper 降级未知。 */
    val status = BackupFailureMessageMapper.httpStatus(reasonCode)
    /** UI 仅选择资源，业务分类在既有纯 mapper 中维护。 */
    val resource = when (BackupFailureMessageMapper.map(reasonCode)) {
        BackupFailureMessage.Storage -> BaseR.string.base_general_backup_error_storage
        BackupFailureMessage.LocalDirectoryConflict -> BaseR.string.base_general_backup_error_local_directory_conflict
        BackupFailureMessage.Auth -> R.string.host_backup_error_auth_reason
        BackupFailureMessage.Remote -> R.string.host_backup_error_remote_reason
        BackupFailureMessage.Space -> BaseR.string.base_general_backup_error_insufficient_space
        BackupFailureMessage.TempFile -> R.string.host_backup_error_package_unavailable
        BackupFailureMessage.Interrupted -> R.string.host_backup_error_upload_interrupted
        BackupFailureMessage.Timeout -> R.string.host_backup_error_upload_timeout
        BackupFailureMessage.Quota -> R.string.host_backup_error_upload_quota
        BackupFailureMessage.Network -> R.string.host_backup_error_upload_network_interrupted
        BackupFailureMessage.DeviceState -> R.string.host_backup_error_upload_device_interrupted
        BackupFailureMessage.Cancelled -> R.string.host_backup_error_upload_cancelled
        BackupFailureMessage.Schedule -> R.string.host_backup_error_schedule
        BackupFailureMessage.Handoff -> R.string.host_backup_error_handoff
        BackupFailureMessage.RequestTimeout -> R.string.host_backup_error_request_timeout
        BackupFailureMessage.Tls -> R.string.host_backup_error_tls
        BackupFailureMessage.RequestNetwork -> R.string.host_backup_error_request_network
        BackupFailureMessage.DirectoryCreate -> R.string.host_backup_error_directory_create
        BackupFailureMessage.NoReason -> R.string.host_backup_error_no_reason
        BackupFailureMessage.Unknown -> R.string.host_backup_error_unknown_reason
        BackupFailureMessage.Http -> when (status) {
            404 -> R.string.host_backup_error_http_missing
            409 -> R.string.host_backup_error_http_conflict
            else -> R.string.host_backup_error_http
        }
    }
    return if (status != null) stringResource(resource, status) else stringResource(resource)
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
            BackupResultPresentation(BackupResultKind.Failed, reasonCode = "webdav_request_timeout")))
    }
}

/** HTTP 冲突和缺失历史原因同屏覆盖自然换行，视觉与读屏仍需设备验收。 */
@Preview(widthDp = 220, fontScale = 1.6f, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackupFailureReasonsPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        BackupResultsSummary(BackupEntryState(BackupResultPresentation(BackupResultKind.Failed),
            BackupResultPresentation(BackupResultKind.Failed, reasonCode = "webdav_http_409")))
    }
}

/** 同名目录指引较长，覆盖两个页面共用摘要在窄屏和大字体下的自然换行。 */
@Preview(widthDp = 220, fontScale = 1.6f)
@Composable
private fun BackupLocalDirectoryConflictPreview() {
    MaterialTheme {
        BackupResultsSummary(BackupEntryState(
            local = BackupResultPresentation(BackupResultKind.Failed, reasonCode = "local_directory_conflict"),
            webDav = BackupResultPresentation(BackupResultKind.Success, completedAt = 100L),
        ))
    }
}
