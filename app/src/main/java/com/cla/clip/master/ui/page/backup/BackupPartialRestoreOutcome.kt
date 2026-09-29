package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.BackupFeatureRestoreFailure
import com.cla.clip.base.general.backup.BackupRestoreReport
import com.cla.clip.base.general.backup.BackupSettingsRestoreFailure

/** 恢复已提交宿主数据后失败的具体阶段，供 UI 显示准确的部分成功提示。 */
internal enum class BackupPartialRestoreStage {
    /** 可选功能库阶段未完整完成，统计仅承诺宿主 Room 已提交部分。 */
    Feature,
    /** 宿主和功能数据已完成，但独立 DataStore 设置未提交。 */
    Settings,
}

/** 可恢复的部分结果，仅包含已确认落盘的数量与失败阶段。 */
internal data class BackupPartialRestoreOutcome(
    /** 已成功提交阶段的报告，禁止补入失败阶段的估计数量。 */
    val report: BackupRestoreReport,
    /** 需要用户重试的阶段。 */
    val stage: BackupPartialRestoreStage,
)

/** 只识别明确携带已提交报告的异常；普通失败仍由原有错误流程处理。 */
internal fun Throwable.toPartialRestoreOutcomeOrNull(): BackupPartialRestoreOutcome? = when (this) {
    is BackupFeatureRestoreFailure -> BackupPartialRestoreOutcome(roomReport, BackupPartialRestoreStage.Feature)
    is BackupSettingsRestoreFailure -> BackupPartialRestoreOutcome(roomReport, BackupPartialRestoreStage.Settings)
    else -> null
}
