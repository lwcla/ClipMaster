package com.cla.clip.master.ui.page.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.media.MediaRelocationCategoryReport
import com.cla.clip.master.media.MediaRelocationDurationLevel
import com.cla.clip.master.media.MediaRelocationPreparation
import com.cla.clip.master.media.MediaRelocationProgress
import com.cla.clip.master.media.MediaRelocationReport
import com.cla.clip.master.media.MediaRelocationStage

/** 媒体关联预估结果，用于独立页和恢复页共享展示。 */
@Composable
internal fun MediaRelocationEstimateRows(preparation: MediaRelocationPreparation) {
    val estimate = preparation.estimate
    Text(
        text = stringResource(
            R.string.base_general_backup_media_relocation_estimate,
            estimate.videoCount,
            estimate.imageBatchCount,
            estimate.imageItemCount,
            estimate.durationLevel.labelText()
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = stringResource(R.string.base_general_backup_media_relocation_estimate_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 媒体关联正式扫描进度，用于独立页运行态展示。 */
@Composable
internal fun MediaRelocationProgressRows(
    progress: MediaRelocationProgress,
    progressRow: @Composable (String) -> Unit,
) {
    progressRow(stringResource(R.string.base_general_backup_media_relocation_running))
    Text(
        text = stringResource(
            R.string.base_general_backup_media_relocation_progress,
            progress.stage.labelText(),
            progress.processedVideos,
            progress.totalVideos,
            progress.processedImageBatches,
            progress.totalImageBatches,
            progress.processedImageItems,
            progress.totalImageItems,
            progress.relocatedCount
        ),
        style = MaterialTheme.typography.bodyMedium
    )
}

/** 媒体关联 summary 的一行结论，由结构化类型和数字现场格式化。 */
@Composable
internal fun MediaRelocationSummaryText(summary: MediaRelocationSummary) {
    val resId = when (summary.type) {
        MediaRelocationSummaryType.NoWork -> R.string.base_general_backup_media_relocation_summary_no_work
        MediaRelocationSummaryType.Completed -> R.string.base_general_backup_media_relocation_summary_completed
        MediaRelocationSummaryType.PermissionDenied -> R.string.base_general_backup_media_relocation_summary_permission
        MediaRelocationSummaryType.Failed -> R.string.base_general_backup_media_relocation_summary_failed
        MediaRelocationSummaryType.Interrupted -> R.string.base_general_backup_media_relocation_summary_interrupted
    }
    Text(
        text = stringResource(
            resId,
            summary.totalExistingReadable,
            summary.totalRelocated,
            summary.totalUnresolved
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (summary.type == MediaRelocationSummaryType.Completed || summary.type == MediaRelocationSummaryType.NoWork) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/** 媒体关联视频和图片明细数字，用于独立页结果态和恢复页回显。 */
@Composable
internal fun MediaRelocationResultRows(report: MediaRelocationReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(
                R.string.base_general_backup_media_relocation_result_video,
                report.video.existingReadable,
                report.video.relocated,
                report.video.noCandidate + report.video.folderMissing,
                report.video.multipleCandidates,
                report.video.metadataMismatch,
                report.video.permissionDenied,
                report.video.writeFailed
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(
                R.string.base_general_backup_media_relocation_result_image,
                report.image.existingReadable,
                report.image.relocated,
                report.imageFolderMissingBatches,
                report.image.noCandidate + report.image.folderMissing,
                report.image.multipleCandidates,
                report.image.metadataMismatch,
                report.image.permissionDenied,
                report.image.writeFailed
            ),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 从准备结果生成旧引用仍可读的轻量报告，避免 UI 自行拼字段。 */
internal fun MediaRelocationPreparation.toExistingReadableReport(): MediaRelocationReport {
    return MediaRelocationReport(
        video = MediaRelocationCategoryReport(existingReadable = existingReadableVideoCount),
        image = MediaRelocationCategoryReport(existingReadable = existingReadableImageCount)
    )
}

@Composable
internal fun MediaRelocationDurationLevel.labelText(): String {
    return when (this) {
        MediaRelocationDurationLevel.None -> stringResource(R.string.base_general_backup_media_relocation_estimate_none)
        MediaRelocationDurationLevel.Seconds -> stringResource(R.string.base_general_backup_media_relocation_estimate_seconds)
        MediaRelocationDurationLevel.TensOfSeconds -> stringResource(R.string.base_general_backup_media_relocation_estimate_tens)
        MediaRelocationDurationLevel.Minutes -> stringResource(R.string.base_general_backup_media_relocation_estimate_minutes)
    }
}

@Composable
internal fun MediaRelocationStage.labelText(): String {
    return when (this) {
        MediaRelocationStage.VerifyingExisting -> stringResource(R.string.base_general_backup_media_stage_verify)
        MediaRelocationStage.ScanningVideos -> stringResource(R.string.base_general_backup_media_stage_video)
        MediaRelocationStage.ScanningImages -> stringResource(R.string.base_general_backup_media_stage_image)
        MediaRelocationStage.Completed -> stringResource(R.string.base_general_backup_media_stage_done)
    }
}
