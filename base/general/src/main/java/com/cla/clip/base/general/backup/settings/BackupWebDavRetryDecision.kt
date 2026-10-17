package com.cla.clip.base.general.backup.settings

/** 重试事务的编译期结果，不序列化，不依赖类名稳定或反射。 */
sealed interface BackupWebDavRetryDecision {
    /** 本次额度和等待阶段均已持久化，调用方才可向系统请求重试。 */
    data class Queued(
        /** 当前轮次已批准的业务重试次数，不包含系统暂停重入。 */
        val retryCount: Int,
    ) : BackupWebDavRetryDecision
    /** 当前有效轮次已用完业务额度，调用方应保存最后一次真实失败。 */
    data object Exhausted : BackupWebDavRetryDecision
    /** 轮次过期、已终结或包尚未完整，不再请求网络或覆盖结果。 */
    data object Obsolete : BackupWebDavRetryDecision

    companion object {
        /** 每轮仅批准两次额外补传，避免持续网络故障无限消耗资源。 */
        const val MAX_RETRIES = 2
    }
}
