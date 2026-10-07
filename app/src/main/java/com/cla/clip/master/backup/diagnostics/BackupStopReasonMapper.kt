package com.cla.clip.master.backup.diagnostics

import androidx.work.WorkInfo

/** 停止原因仅细化本次已确认的上传中断，不改变重试与终态策略。 */
internal object BackupStopReasonMapper {
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
