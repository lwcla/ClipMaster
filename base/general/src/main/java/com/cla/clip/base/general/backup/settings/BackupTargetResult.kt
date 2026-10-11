package com.cla.clip.base.general.backup.settings

/** 结果归属仅在编译期使用，不依赖枚举名称持久化。 */
enum class BackupResultTarget { Local, WebDav }

/** 最近一次已结束的备份；等待和运行不覆盖历史结论。 */
data class BackupTargetResult(
    /** 完成时间，零表示没有可归属的历史结果。 */
    val completedAt: Long = 0L,
    /** 真正失败才置位，不包含网络等待。 */
    val failed: Boolean = false,
    /** 低敏稳定错误码，不保存异常原文或凭据。 */
    val reasonCode: String = "",
)

/** 安装内文件身份；固定字段排除用户备份，不包含业务版本或凭据。 */
data class BackupPendingUpload(
    /** 私有文件身份，旧任务只能处理匹配身份。 */
    val artifactId: String,
    /** 目标端点、账号、目录的不可逆摘要。 */
    val targetFingerprint: String,
    /** 稳定来源 code：auto 或 manual。 */
    val source: String,
    /** 云端稳定阶段：generating、ready、queued、uploading、succeeded、failed、cancelled、result_store_failed、unselected。 */
    val phase: String,
    /** 快照创建时间，只用于诊断。 */
    val createdAt: Long,
    /** 生成时本地目录的摘要，消费时与当前目标比较。 */
    val localTargetFingerprint: String = "",
    /** 本地独立阶段，与云端阶段互不覆盖。 */
    val localPhase: String = "unselected",
    /** 安装内当前任务的 404/409 共用处理标记，系统重调度不重置，也不进入备份。 */
    val webDavDirectoryRecoveryAttempted: Boolean = false,
) {
    /** 云端已结束时不再请求；共享包仍由下一轮清理。 */
    val terminal: Boolean get() = isTerminalPhase(phase)

    companion object {
        /** 终态不得再次推进或覆盖完成结果，集中维护固定阶段白名单。 */
        internal fun isTerminalPhase(phase: String?): Boolean = phase in setOf("succeeded", "failed", "cancelled", "result_store_failed", "unselected")
    }
}
