package com.cla.clip.master.backup.diagnostics

import androidx.work.WorkInfo

/** 系统停止分类只使用当次平台证据；可恢复暂停与用户/应用取消分开。 */
internal object BackupStopReasonMapper {
    /** 平台抢占及系统处理同样等待重调度，不能保存失败终态；用户/应用取消及未知证据仍不恢复。 */
    fun canResume(stopReason: Int): Boolean = stopReason in setOf(
        WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_QUOTA, WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
        WorkInfo.STOP_REASON_DEVICE_STATE, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, WorkInfo.STOP_REASON_APP_STANDBY,
        WorkInfo.STOP_REASON_PREEMPT, WorkInfo.STOP_REASON_SYSTEM_PROCESSING)
    /** 系统未提供可行动证据时保留旧通用中断码。 */
    fun interruptionCode(stopReason: Int): String = when (stopReason) {
        WorkInfo.STOP_REASON_TIMEOUT -> "upload_interrupted_timeout"
        WorkInfo.STOP_REASON_QUOTA -> "upload_interrupted_quota"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "upload_interrupted_network"
        WorkInfo.STOP_REASON_DEVICE_STATE, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
        WorkInfo.STOP_REASON_APP_STANDBY -> "upload_interrupted_device"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP, WorkInfo.STOP_REASON_USER -> "upload_interrupted_cancelled"
        else -> "upload_interrupted"
    }
}
