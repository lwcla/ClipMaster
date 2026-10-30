package com.cla.clip.base.general.backup

import java.util.concurrent.atomic.AtomicInteger
import com.cla.clip.base.general.backup.webdav.WebDavHttpFailure
import com.cla.clip.base.general.backup.webdav.webDavEvidence
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** 备份日志短 id 计数器，和时间戳组合后足够串起单进程内的一次备份/恢复任务。 */
private val backupTaskCounter = AtomicInteger(0)

/**
 * 创建一次备份/恢复流程的短 taskId。
 *
 * taskId 只用于日志串联，不进入备份协议，也不包含账号、路径、剪贴内容或其它用户数据。
 */
fun newBackupTaskId(prefix: String): String {
    val timePart = System.currentTimeMillis().toString(36)
    val countPart = backupTaskCounter.updateAndGet { value ->
        if (value >= 46655) 0 else value + 1
    }.toString(36)
    return "$prefix-$timePart-$countPart"
}

/**
 * 生成可拼接到日志里的 taskId 字段。
 *
 * 部分底层工具方法可能被旧调用方复用而没有传入 taskId；此时返回空串，避免伪造链路。
 */
fun backupTaskLogField(taskId: String?): String {
    return taskId?.takeIf { it.isNotBlank() }?.let { "taskId=$it " }.orEmpty()
}

/** 备份类型的稳定日志值，避免直接依赖枚举 `name` 作为长期排障字段。 */
fun BackupKind.logCode(): String {
    return when (this) {
        BackupKind.Manual -> "manual"
        BackupKind.Auto -> "auto"
        BackupKind.Safety -> "safety"
    }
}

/** 备份来源的稳定日志值，避免日志字段随 Kotlin 枚举名调整而变化。 */
fun BackupSource.logCode(): String {
    return when (this) {
        BackupSource.LocalManual -> "local_manual"
        BackupSource.LocalAuto -> "local_auto"
        BackupSource.WebDavManual -> "webdav_manual"
        BackupSource.WebDavAuto -> "webdav_auto"
    }
}

/** 将备份摘要转成脱敏数量字段；只包含数量，不包含剪贴内容、搜索词或下载 URL。 */
fun BackupSummary.toLogFields(): String {
    // 模块摘要按稳定标识排序，只记录数量。
    val featureCountFields = featureCounts
        .toSortedMap()
        .entries
        .joinToString(prefix = " featureCounts=[", postfix = "]") { (key, value) -> "$key=$value" }
        .takeIf { featureCounts.isNotEmpty() }
        .orEmpty()
    return "clips=$clipCount sourceApps=$sourceAppCount linkPreviews=$linkPreviewCount " +
        "searchHistories=$searchHistoryCount$featureCountFields"
}

/** 备份失败的稳定 reasonCode，用于日志、重试判断和排障搜索。 */
fun BackupFailure.reasonCode(): String {
    return when (this) {
        is BackupFailure.InvalidFormat -> "invalid_format"
        is BackupFailure.AppMismatch -> "app_mismatch"
        is BackupFailure.UnsupportedSchema -> "schema_unsupported"
        is BackupFailure.ChecksumMismatch -> "checksum_mismatch"
        is BackupFailure.StorageNotWritable -> "storage_not_writable"
        is BackupFailure.LocalDirectoryConflict -> "local_directory_conflict"
        is BackupFailure.AuthenticationFailed -> "auth_failed"
        is BackupFailure.RemoteFailed -> {
            /** 有界展开协程复制的同类异常，仍仅按类型/状态分类，不读取 message。 */
            val evidence = webDavEvidence()
            when (evidence) {
                is WebDavHttpFailure -> if (evidence.operation == "mkcol") "webdav_directory_create_failed" else "webdav_http_${evidence.statusCode}"
                is SocketTimeoutException -> "webdav_request_timeout"
                is SSLException -> "webdav_tls_failed"
                is IOException -> "webdav_network_failed"
                else -> "remote_failed"
            }
        }
        is BackupFailure.FileTooLarge -> "file_too_large"
        is BackupFailure.ParseFailed -> "parse_failed"
        is BackupFailure.TempFileUnavailable -> "temp_file_unavailable"
        is BackupFailure.InsufficientSpace -> "insufficient_space"
    }
}

/**
 * 将任意异常映射成可行动或可搜索的 reasonCode。
 *
 * 未归类异常不拼接 message，避免系统异常里带出本地 URI、URL 或文件路径。
 */
fun Throwable.backupReasonCode(): String {
    return if (this is BackupFailure) reasonCode() else "unexpected_error"
}
