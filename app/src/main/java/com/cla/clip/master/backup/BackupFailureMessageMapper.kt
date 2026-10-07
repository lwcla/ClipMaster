package com.cla.clip.master.backup

/** 纯展示分类，不序列化，不将类型名称作为稳定错误协议。 */
internal enum class BackupFailureMessage { Storage, Auth, Remote, Space, TempFile, Interrupted, Timeout, Quota, Network, DeviceState, Cancelled, Schedule, Handoff, Unknown }

/** 原因码映射独立于 Compose，旧记录也能显示明确的中断提示。 */
internal object BackupFailureMessageMapper {
    /** 未知原因不猜测服务器、网络或系统停止原因。 */
    fun map(reasonCode: String): BackupFailureMessage = when (reasonCode) {
        "storage_not_writable" -> BackupFailureMessage.Storage
        "auth_failed" -> BackupFailureMessage.Auth
        "remote_failed" -> BackupFailureMessage.Remote
        "insufficient_space" -> BackupFailureMessage.Space
        "temp_file_unavailable" -> BackupFailureMessage.TempFile
        "upload_interrupted" -> BackupFailureMessage.Interrupted
        "upload_interrupted_timeout" -> BackupFailureMessage.Timeout
        "upload_interrupted_quota" -> BackupFailureMessage.Quota
        "upload_interrupted_network" -> BackupFailureMessage.Network
        "upload_interrupted_device" -> BackupFailureMessage.DeviceState
        "upload_interrupted_cancelled" -> BackupFailureMessage.Cancelled
        "schedule_failed" -> BackupFailureMessage.Schedule
        "handoff_failed" -> BackupFailureMessage.Handoff
        else -> BackupFailureMessage.Unknown
    }
}
