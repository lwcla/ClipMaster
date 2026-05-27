package com.cla.clip.master.provider

/**
 * 图标同步决策结果。
 *
 * Provider 图标预判阶段和 commit_icon 复用同一套规则，避免“是否要补图”的判断在多处漂移。
 */
data class ClipboardBridgeIconSyncDecision(
    /** 当前来源图标是否需要继续同步。 */
    val shouldSyncIcon: Boolean,
    /** 命中坏路径时是否需要先清空数据库中的旧图标缓存。 */
    val clearStaleCache: Boolean,
    /** 当前决策原因，必须来自 ClipboardBridgeContract 的固定常量集合。 */
    val reasonCode: String,
)
