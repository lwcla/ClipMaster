package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.BackupPreview
import com.cla.clip.base.general.backup.BackupProgress
import com.cla.clip.base.general.backup.BackupRestoreReport
import com.cla.clip.master.media.MediaRelocationEstimate
import com.cla.clip.master.media.MediaRelocationPreparation
import com.cla.clip.master.media.MediaRelocationProgress
import com.cla.clip.master.media.MediaRelocationReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户从哪里打开待恢复备份。
 *
 * 该状态只服务页面展示和低敏日志，不等同于备份 manifest 的 `source` 字段，避免把“打开入口”和“备份生成方式”混为一谈。
 */
enum class BackupRestoreOpenSource(val logCode: String) {
    /** 通过系统文件选择器选择的备份文件。 */
    LocalFile("local_file"),

    /** 从用户授权的本地备份目录列表打开。 */
    LocalDirectory("local_directory"),

    /** 从 WebDAV 备份列表下载打开。 */
    WebDav("webdav"),

    /** 状态缺失时的兜底值，只用于异常恢复和日志。 */
    Unknown("unknown"),
}

/** 备份读取阶段，用于恢复流程页展示更具体的读取反馈。 */
enum class BackupRestoreReadPhase(val logCode: String) {
    /** 本地文件或本地目录条目正在复制到私有临时文件并校验。 */
    CopyingAndVerifying("copying_and_verifying"),

    /** WebDAV 条目正在下载到私有临时文件并校验。 */
    DownloadingAndVerifying("downloading_and_verifying"),
}

/** 恢复失败发生的阶段，用于选择失败页主动作和可行动提示。 */
enum class BackupRestoreFailureStage(val logCode: String) {
    /** 读取、下载、复制或校验阶段失败。 */
    Reading("reading"),

    /** 恢复写库阶段失败。 */
    Restoring("restoring"),
}

/**
 * 备份恢复流程页状态。
 *
 * 一个 sealed class 承载整个流程，避免用多个 Boolean 组合维护读取、预览、恢复和结果的互斥关系。
 */
sealed class BackupRestoreFlowState {
    /** 未显示恢复流程页。 */
    data object Hidden : BackupRestoreFlowState()

    /** 正在读取、下载或校验备份。 */
    data class Reading(
        val taskId: String,
        val sourceType: BackupRestoreOpenSource,
        val readPhase: BackupRestoreReadPhase,
        val progress: BackupProgress?,
    ) : BackupRestoreFlowState()

    /** 已完成预检，等待用户确认恢复。 */
    data class Preview(
        val taskId: String,
        val sourceType: BackupRestoreOpenSource,
        val preview: BackupPreview,
        val fileName: String,
        val fileSize: Long,
    ) : BackupRestoreFlowState()

    /** 正在恢复，进入该状态后不可关闭。 */
    data class Restoring(
        val taskId: String,
        val sourceType: BackupRestoreOpenSource,
        val progress: BackupProgress?,
    ) : BackupRestoreFlowState()

    /** 恢复完成，展示真实写库结果。 */
    data class Result(
        val taskId: String,
        val sourceType: BackupRestoreOpenSource,
        val fileName: String,
        val preview: BackupPreview?,
        val report: BackupRestoreReport,
        val completedAt: Long,
    ) : BackupRestoreFlowState()

    /** 读取或恢复失败，展示统一 reasonCode 映射后的可行动提示。 */
    data class Error(
        val taskId: String,
        val sourceType: BackupRestoreOpenSource,
        val stage: BackupRestoreFailureStage,
        val reasonCode: String,
        val message: String,
    ) : BackupRestoreFlowState()
}

/** 恢复流程状态低敏日志值，保持稳定便于排查状态卡住问题。 */
val BackupRestoreFlowState.logCode: String
    get() = when (this) {
        BackupRestoreFlowState.Hidden -> "hidden"
        is BackupRestoreFlowState.Reading -> "reading"
        is BackupRestoreFlowState.Preview -> "preview"
        is BackupRestoreFlowState.Restoring -> "restoring"
        is BackupRestoreFlowState.Result -> "result"
        is BackupRestoreFlowState.Error -> "error"
    }

/** 从恢复流程状态提取打开来源；状态缺失时使用 Unknown，避免日志或 UI 崩溃。 */
fun BackupRestoreFlowState.sourceTypeOrUnknown(): BackupRestoreOpenSource {
    return when (this) {
        BackupRestoreFlowState.Hidden -> BackupRestoreOpenSource.Unknown
        is BackupRestoreFlowState.Reading -> sourceType
        is BackupRestoreFlowState.Preview -> sourceType
        is BackupRestoreFlowState.Restoring -> sourceType
        is BackupRestoreFlowState.Result -> sourceType
        is BackupRestoreFlowState.Error -> sourceType
    }
}

/** 把毫秒时间格式化为备份页面展示文本。 */
fun Long.toBackupDisplayTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(this))
}

/**
 * 恢复完成后的下载媒体重新定位状态。
 *
 * 该状态只保存在当前恢复页内，不持久化为后台任务；重新进入 App 后必须由用户再次手动触发。
 */
sealed class MediaRelocationUiState {
    /** 尚未开始检查。 */
    data object Idle : MediaRelocationUiState()

    /** 正在做数据库轻量预估。 */
    data object Estimating : MediaRelocationUiState()

    /** 预估为 0 或旧引用全部可读，不需要正式扫描。 */
    data class NoWork(val preparation: MediaRelocationPreparation) : MediaRelocationUiState()

    /** 已完成预估和旧引用验证，等待用户确认正式扫描。 */
    data class ReadyToConfirm(val preparation: MediaRelocationPreparation) : MediaRelocationUiState()

    /** 需要用户授予媒体读取权限后才能继续扫描，可携带上一次拒绝/部分授权后的低敏报告。 */
    data class PermissionRequired(
        val preparation: MediaRelocationPreparation,
        val report: MediaRelocationReport? = null,
    ) : MediaRelocationUiState()

    /** 正在正式扫描和写回；页面内返回不应退出或取消。 */
    data class Running(val progress: MediaRelocationProgress) : MediaRelocationUiState()

    /** 扫描完成，展示当前页内结果。 */
    data class Result(val estimate: MediaRelocationEstimate, val report: MediaRelocationReport) : MediaRelocationUiState()

    /** 扫描或预估失败。 */
    data class Error(val message: String) : MediaRelocationUiState()
}

val MediaRelocationUiState.isRunning: Boolean
    get() = this is MediaRelocationUiState.Running
