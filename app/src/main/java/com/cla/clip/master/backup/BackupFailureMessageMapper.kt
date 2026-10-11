package com.cla.clip.master.backup

/** 纯展示分类，不序列化，不将类型名称作为稳定错误协议。 */
internal enum class BackupFailureMessage {
    Storage, Auth, Remote, Space, TempFile, Interrupted, Timeout, Quota, Network, DeviceState, Cancelled, Schedule, Handoff,
    RequestTimeout, Tls, RequestNetwork, DirectoryCreate, Http, NoReason, Unknown
}

/** 原因码映射独立于 Compose，旧记录也能显示明确的中断提示。 */
internal object BackupFailureMessageMapper {
    /** 未知原因不猜测服务器、网络或系统停止原因。 */
    fun map(reasonCode: String): BackupFailureMessage = when (reasonCode) {
        "" -> BackupFailureMessage.NoReason
        "webdav_request_timeout" -> BackupFailureMessage.RequestTimeout
        "webdav_tls_failed" -> BackupFailureMessage.Tls
        "webdav_network_failed" -> BackupFailureMessage.RequestNetwork
        "webdav_directory_create_failed" -> BackupFailureMessage.DirectoryCreate
        "storage_not_writable" -> BackupFailureMessage.Storage
        "auth_failed" -> BackupFailureMessage.Auth
        "remote_failed" -> BackupFailureMessage.Remote
        "insufficient_space" -> BackupFailureMessage.Space
        "temp_file_unavailable" -> BackupFailureMessage.TempFile
        "upload_interrupted", "generation_interrupted", "local_interrupted" -> BackupFailureMessage.Interrupted
        "upload_interrupted_timeout" -> BackupFailureMessage.Timeout
        "upload_interrupted_quota" -> BackupFailureMessage.Quota
        "upload_interrupted_network" -> BackupFailureMessage.Network
        "upload_interrupted_device" -> BackupFailureMessage.DeviceState
        "upload_interrupted_cancelled" -> BackupFailureMessage.Cancelled
        "schedule_failed" -> BackupFailureMessage.Schedule
        "handoff_failed" -> BackupFailureMessage.Handoff
        else -> if (httpStatus(reasonCode) != null) BackupFailureMessage.Http else BackupFailureMessage.Unknown
    }

    /** 只解析固定格式的 400–599 状态，不接受多余符号、异常原文、Unicode 数字或越界内容。 */
    fun httpStatus(reasonCode: String): Int? {
        if (!reasonCode.startsWith("webdav_http_")) return null
        /** 三位 ASCII HTTP 错误码来自共享请求层，不拼接外部文本。 */
        val suffix = reasonCode.removePrefix("webdav_http_")
        if (suffix.length != 3 || suffix.any { it !in '0'..'9' }) return null
        return suffix.toIntOrNull()?.takeIf { it in 400..599 }
    }
}
