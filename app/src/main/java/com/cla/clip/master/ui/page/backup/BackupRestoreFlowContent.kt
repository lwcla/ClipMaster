package com.cla.clip.master.ui.page.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.base.general.backup.BackupKind
import com.cla.clip.base.general.backup.BackupPreview
import com.cla.clip.base.general.backup.BackupProgress
import com.cla.clip.base.general.backup.BackupProgressCategory
import com.cla.clip.base.general.backup.BackupProgressPhase
import com.cla.clip.base.general.backup.BackupRestoreCategoryReport
import com.cla.clip.base.general.backup.BackupSummary

/**
 * 备份恢复流程页内容区。
 *
 * 只负责把恢复流程状态和媒体关联回显渲染为摘要、进度和报告，不启动文件、网络或数据库副作用。
 */
@Composable
internal fun BackupRestoreFlowContent(
    state: BackupRestoreFlowState,
    mediaRelocationEntryState: MediaRelocationEntryState,
    mediaRelocationSummary: MediaRelocationSummary?,
) {
    FlowHeader(state = state)
    when (state) {
        is BackupRestoreFlowState.Reading -> ReadingContent(state)
        is BackupRestoreFlowState.Preview -> PreviewContent(state)
        is BackupRestoreFlowState.Restoring -> RestoringContent(state)
        is BackupRestoreFlowState.Result -> ResultContent(
            state = state,
            mediaRelocationEntryState = mediaRelocationEntryState,
            mediaRelocationSummary = mediaRelocationSummary
        )
        is BackupRestoreFlowState.Error -> ErrorContent(state)
        BackupRestoreFlowState.Hidden -> Unit
    }
}

/** 备份恢复流程页底部操作区。 */
@Composable
internal fun BackupRestoreFlowActions(
    state: BackupRestoreFlowState,
    mediaRelocationEntryState: MediaRelocationEntryState,
    onBack: () -> Unit,
    onRestore: () -> Unit,
    onOpenMediaRelocation: () -> Unit,
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
            is BackupRestoreFlowState.Preview -> {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.base_general_cancel))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onRestore) {
                    Text(stringResource(R.string.base_general_restore_backup))
                }
            }
            is BackupRestoreFlowState.Result -> {
                MediaRelocationAction(
                    state = mediaRelocationEntryState,
                    onOpenMediaRelocation = onOpenMediaRelocation
                )
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = onBack,
                    enabled = !mediaRelocationEntryState.isRunning
                ) {
                    Text(stringResource(R.string.base_general_backup_flow_done))
                }
            }
            is BackupRestoreFlowState.Error -> {
                Button(onClick = onBack) {
                    Text(
                        if (state.stage == BackupRestoreFailureStage.Reading) {
                            stringResource(R.string.base_general_backup_flow_retry_select)
                        } else {
                            stringResource(R.string.base_general_backup_flow_close)
                        }
                    )
                }
            }
            is BackupRestoreFlowState.Reading,
            is BackupRestoreFlowState.Restoring,
            BackupRestoreFlowState.Hidden -> Unit
        }
    }
}

@Composable
private fun FlowHeader(state: BackupRestoreFlowState) {
    val visual = state.statusVisual()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = state.statusText(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = visual.tint
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.base_general_backup_flow_current_status, state.statusText()),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = visual.tint
                )
                Text(
                    text = stringResource(
                        R.string.base_general_backup_flow_status_detail,
                        state.sourceTypeOrUnknown().labelText(),
                        state.previewOrNull()?.backupKind.labelText(),
                        state.previewOrNull()?.createdAt?.toBackupDisplayTime()
                            ?: stringResource(R.string.base_general_backup_flow_status_reading)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReadingContent(state: BackupRestoreFlowState.Reading) {
    ProgressRow(text = state.readPhase.labelText())
}

@Composable
private fun RestoringContent(state: BackupRestoreFlowState.Restoring) {
    ProgressRow(text = progressMessage(state.progress))
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

@Composable
private fun PreviewContent(state: BackupRestoreFlowState.Preview) {
    val preview = state.preview
    FlowSection(title = stringResource(R.string.base_general_backup_section_info)) {
        InfoRow(stringResource(R.string.base_general_backup_info_file_name), state.fileName, keepEnd = true)
        InfoRow(stringResource(R.string.base_general_backup_info_open_source), state.sourceType.labelText())
        InfoRow(stringResource(R.string.base_general_backup_info_kind), preview.backupKind.labelText())
        InfoRow(stringResource(R.string.base_general_backup_info_created_at), preview.createdAt.toBackupDisplayTime())
        InfoRow(stringResource(R.string.base_general_backup_info_app_version), preview.appVersionName.ifBlank { unknownText() })
        InfoRow(stringResource(R.string.base_general_backup_info_schema), preview.schemaVersion.toString())
        InfoRow(stringResource(R.string.base_general_backup_info_file_size), formatBackupSize(state.fileSize))
        InfoRow(stringResource(R.string.base_general_backup_info_device), preview.deviceLabel.ifBlank { unknownText() })
        InfoRow(
            stringResource(R.string.base_general_backup_info_checksum),
            if (preview.checksumValid) {
                stringResource(R.string.base_general_backup_checksum_passed)
            } else {
                stringResource(R.string.base_general_backup_checksum_failed)
            }
        )
    }
    FlowSection(title = stringResource(R.string.base_general_backup_section_counts)) {
        BackupCountRows(summary = preview.summary)
    }
    FlowSection(title = stringResource(R.string.base_general_backup_section_restore_note)) {
        Text(
            text = stringResource(R.string.base_general_backup_restore_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultContent(
    state: BackupRestoreFlowState.Result,
    mediaRelocationEntryState: MediaRelocationEntryState,
    mediaRelocationSummary: MediaRelocationSummary?,
) {
    val report = state.report
    FlowSection(title = stringResource(R.string.base_general_backup_section_info)) {
        state.preview?.let { preview ->
            InfoRow(stringResource(R.string.base_general_backup_info_file_name), state.fileName, keepEnd = true)
            InfoRow(stringResource(R.string.base_general_backup_info_open_source), state.sourceType.labelText())
            InfoRow(stringResource(R.string.base_general_backup_info_kind), preview.backupKind.labelText())
        }
        InfoRow(
            stringResource(R.string.base_general_backup_info_restore_time),
            state.completedAt.toBackupDisplayTime()
        )
        InfoRow(
            label = stringResource(R.string.base_general_backup_flow_result_title),
            value = stringResource(
                R.string.base_general_backup_restore_result_summary,
                report.insertedCount,
                report.updatedCount,
                report.skippedCount
            )
        )
    }
    FlowSection(title = stringResource(R.string.base_general_backup_section_counts)) {
        RestoreCategoryRows(report.categoryReports)
    }
    Text(
        text = stringResource(R.string.base_general_backup_restore_skip_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.base_general_backup_restore_image_item_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    MediaRelocationEntryContent(
        entryState = mediaRelocationEntryState,
        summary = mediaRelocationSummary
    )
}

@Composable
private fun ErrorContent(state: BackupRestoreFlowState.Error) {
    FlowSection(title = state.stage.labelText()) {
        InfoRow(stringResource(R.string.base_general_backup_info_open_source), state.sourceType.labelText())
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = stringResource(R.string.base_general_backup_error_action_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FlowSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun InfoRow(label: String, value: String, keepEnd: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { unknownText() },
            modifier = Modifier.weight(0.66f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (keepEnd) 3 else 2,
            overflow = if (keepEnd) TextOverflow.Ellipsis else TextOverflow.Clip
        )
    }
}

@Composable
private fun BackupCountRows(summary: BackupSummary) {
    BackupCountRow(stringResource(R.string.base_general_backup_count_clips), summary.clipCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_source_apps), summary.sourceAppCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_link_previews), summary.linkPreviewCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_search_histories), summary.searchHistoryCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_magnet_search_histories), summary.magnetSearchHistoryCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_video_downloads), summary.videoDownloadCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_magnet_download_records), summary.magnetDownloadRecordCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_image_batches), summary.imageBatchCount)
    BackupCountRow(stringResource(R.string.base_general_backup_count_image_items), summary.imageItemCount)
}

@Composable
private fun RestoreCategoryRows(reports: List<BackupRestoreCategoryReport>) {
    val byCategory = reports.associateBy { it.category }
    restoreReportCategoryOrder.forEach { category ->
        val report = byCategory[category]
        BackupCountRow(
            label = category.labelText(),
            value = stringResource(
                R.string.base_general_backup_restore_result_summary,
                report?.insertedCount ?: 0,
                report?.updatedCount ?: 0,
                report?.skippedCount ?: 0
            ),
            muted = (report?.insertedCount ?: 0) == 0 &&
                (report?.updatedCount ?: 0) == 0 &&
                (report?.skippedCount ?: 0) == 0
        )
    }
}

@Composable
private fun BackupCountRow(label: String, count: Int) {
    BackupCountRow(label = label, value = count.toString(), muted = count == 0)
}

@Composable
private fun BackupCountRow(label: String, value: String, muted: Boolean) {
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun MediaRelocationEntryContent(
    entryState: MediaRelocationEntryState,
    summary: MediaRelocationSummary?,
) {
    FlowSection(title = stringResource(R.string.base_general_backup_media_relocation_title)) {
        if (summary == null) {
            Text(
                text = stringResource(R.string.base_general_backup_media_relocation_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            MediaRelocationSummaryText(summary)
            MediaRelocationResultRows(summary.report)
        }
        if (entryState.isRunning) {
            Text(
                text = stringResource(R.string.base_general_backup_media_relocation_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MediaRelocationAction(
    state: MediaRelocationEntryState,
    onOpenMediaRelocation: () -> Unit,
) {
    when (state) {
        MediaRelocationEntryState.NotStarted -> TextButton(onClick = onOpenMediaRelocation) {
            Text(stringResource(R.string.base_general_backup_media_relocation_start))
        }
        MediaRelocationEntryState.Incomplete -> TextButton(onClick = onOpenMediaRelocation) {
            Text(stringResource(R.string.base_general_backup_media_relocation_continue))
        }
        MediaRelocationEntryState.Running -> TextButton(
            onClick = {},
            enabled = false
        ) {
            Text(stringResource(R.string.base_general_backup_media_relocation_running_short))
        }
        MediaRelocationEntryState.Terminal -> TextButton(onClick = onOpenMediaRelocation) {
            Text(stringResource(R.string.base_general_backup_media_relocation_restart))
        }
    }
}

@Composable
private fun BackupRestoreFlowState.statusText(): String {
    return when (this) {
        BackupRestoreFlowState.Hidden -> ""
        is BackupRestoreFlowState.Reading -> stringResource(R.string.base_general_backup_flow_reading_title)
        is BackupRestoreFlowState.Preview -> stringResource(R.string.base_general_backup_flow_preview_title)
        is BackupRestoreFlowState.Restoring -> stringResource(R.string.base_general_backup_flow_restoring_title)
        is BackupRestoreFlowState.Result -> stringResource(R.string.base_general_backup_flow_result_title)
        is BackupRestoreFlowState.Error -> stringResource(R.string.base_general_backup_flow_error_title)
    }
}

@Composable
private fun BackupRestoreFlowState.statusVisual(): RestoreStatusVisual {
    return when (this) {
        BackupRestoreFlowState.Hidden -> RestoreStatusVisual(
            icon = Icons.Filled.HourglassEmpty,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        is BackupRestoreFlowState.Reading -> RestoreStatusVisual(
            icon = Icons.Filled.HourglassEmpty,
            tint = MaterialTheme.colorScheme.primary
        )
        is BackupRestoreFlowState.Preview -> RestoreStatusVisual(
            icon = Icons.Filled.Visibility,
            tint = MaterialTheme.colorScheme.secondary
        )
        is BackupRestoreFlowState.Restoring -> RestoreStatusVisual(
            icon = Icons.Filled.Restore,
            tint = MaterialTheme.colorScheme.tertiary
        )
        is BackupRestoreFlowState.Result -> RestoreStatusVisual(
            icon = Icons.Filled.CheckCircle,
            tint = RestoreSuccessColor
        )
        is BackupRestoreFlowState.Error -> RestoreStatusVisual(
            icon = Icons.Filled.Error,
            tint = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun BackupRestoreOpenSource.labelText(): String {
    return when (this) {
        BackupRestoreOpenSource.LocalFile -> stringResource(R.string.base_general_backup_open_source_local_file)
        BackupRestoreOpenSource.LocalDirectory -> stringResource(R.string.base_general_backup_open_source_local_directory)
        BackupRestoreOpenSource.WebDav -> stringResource(R.string.base_general_backup_open_source_webdav)
        BackupRestoreOpenSource.Unknown -> stringResource(R.string.base_general_backup_open_source_unknown)
    }
}

@Composable
private fun BackupKind?.labelText(): String {
    return when (this) {
        BackupKind.Manual -> stringResource(R.string.base_general_backup_kind_manual_short)
        BackupKind.Auto -> stringResource(R.string.base_general_backup_kind_auto_short)
        BackupKind.Safety,
        null -> stringResource(R.string.base_general_backup_kind_unknown_short)
    }
}

@Composable
private fun BackupRestoreReadPhase.labelText(): String {
    return when (this) {
        BackupRestoreReadPhase.CopyingAndVerifying -> stringResource(R.string.base_general_backup_reading_local)
        BackupRestoreReadPhase.DownloadingAndVerifying -> stringResource(R.string.base_general_backup_reading_webdav)
    }
}

@Composable
private fun BackupRestoreFailureStage.labelText(): String {
    return when (this) {
        BackupRestoreFailureStage.Reading -> stringResource(R.string.base_general_backup_flow_reading_title)
        BackupRestoreFailureStage.Restoring -> stringResource(R.string.base_general_backup_flow_restoring_title)
    }
}

@Composable
private fun BackupProgressCategory.labelText(): String {
    return when (this) {
        BackupProgressCategory.Clips -> stringResource(R.string.base_general_backup_count_clips)
        BackupProgressCategory.SourceApps -> stringResource(R.string.base_general_backup_count_source_apps)
        BackupProgressCategory.LinkPreviews -> stringResource(R.string.base_general_backup_count_link_previews)
        BackupProgressCategory.SearchHistories -> stringResource(R.string.base_general_backup_count_search_histories)
        BackupProgressCategory.MagnetSearchHistories -> stringResource(R.string.base_general_backup_count_magnet_search_histories)
        BackupProgressCategory.VideoDownloads -> stringResource(R.string.base_general_backup_count_video_downloads)
        BackupProgressCategory.MagnetDownloadRecords -> stringResource(R.string.base_general_backup_count_magnet_download_records)
        BackupProgressCategory.ImageBatches -> stringResource(R.string.base_general_backup_count_image_batches)
        BackupProgressCategory.ImageItems -> stringResource(R.string.base_general_backup_count_image_items)
        BackupProgressCategory.Settings,
        BackupProgressCategory.Overall -> stringResource(R.string.base_general_unknow)
    }
}

@Composable
private fun progressMessage(progress: BackupProgress?): String {
    return when (progress?.phase) {
        BackupProgressPhase.Restoring -> stringResource(R.string.base_general_backup_progress_restoring)
        BackupProgressPhase.Verifying -> stringResource(R.string.base_general_backup_progress_verifying)
        else -> stringResource(R.string.base_general_backup_restoring_message)
    }
}

@Composable
private fun unknownText(): String = stringResource(R.string.base_general_unknow)

private fun BackupRestoreFlowState.previewOrNull(): BackupPreview? {
    return when (this) {
        is BackupRestoreFlowState.Preview -> preview
        is BackupRestoreFlowState.Result -> preview
        else -> null
    }
}

private val restoreReportCategoryOrder = listOf(
    BackupProgressCategory.Clips,
    BackupProgressCategory.SourceApps,
    BackupProgressCategory.LinkPreviews,
    BackupProgressCategory.SearchHistories,
    BackupProgressCategory.MagnetSearchHistories,
    BackupProgressCategory.VideoDownloads,
    BackupProgressCategory.MagnetDownloadRecords,
    BackupProgressCategory.ImageBatches,
    BackupProgressCategory.ImageItems
)

private data class RestoreStatusVisual(
    val icon: ImageVector,
    val tint: Color,
)

private val RestoreSuccessColor = Color(0xFF2E7D32)
