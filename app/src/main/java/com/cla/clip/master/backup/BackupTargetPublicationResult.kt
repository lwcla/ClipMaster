package com.cla.clip.master.backup

/** 编译期上传执行结果；Worker 成功退出也可能只是过期跳过，不冒充远端发布成功。 */
internal sealed interface BackupTargetPublicationResult {
    /** 当前目标成功且结果已持久化。 */
    data object Completed : BackupTargetPublicationResult
    /** 当前身份已失效或终结，不再发送或覆盖结果。 */
    data object Skipped : BackupTargetPublicationResult
    /** 持久化重试批准，Worker 可交回系统等待。 */
    data class RetryScheduled(
        /** 本轮已消费的业务重试额度，独立于系统执行次数。 */
        val retryCount: Int,
    ) : BackupTargetPublicationResult
}
