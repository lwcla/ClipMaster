package com.cla.clip.master.ui.page.mine

/** 页面会话扫描状态，查询、范围和摘要都不持久化，不以控件状态代替可见生命周期。 */
data class InstalledAppListState(
    /** 当前是否正在读取名称及图标，供下拉刷新指示和首次加载展示。 */
    val loading: Boolean = false,
    /** 会话是否有过成功结果，失败或取消不隐式标记成功。 */
    val everLoaded: Boolean = false,
    /** 低敏失败原因，失败继续保留成功摘要及应用快照。 */
    val unavailableReasonCode: String? = null,
    /** 目标系统范围，关闭时搜索和已过滤状态都不能放行系统应用。 */
    val showSystemApps: Boolean = false,
    /** 搜索词仅匹配候选，不改变持久名单。 */
    val searchQuery: String = "",
    /** 上次成功摘要，不因开始刷新或失败丢失。 */
    val lastLoadSummary: InstalledAppLoadSummary? = null,
    /** 最近可用快照的数量，不是完整过滤规则数量。 */
    val loadedAppCount: Int = 0,
) {
    /** 首次成功前允许自动入口重试，扫描范围是否一致另由协调器判断。 */
    internal val needsInitialLoad: Boolean
        get() = !loading && !everLoaded
}

/** 成功扫描摘要，仅用于页面展示与低敏诊断。 */
data class InstalledAppLoadSummary(
    /** 当前扫描范围应用数量，不含未扫描到的规则。 */
    val appCount: Int,
    /** 本次 IO 耗时毫秒，不用于节流。 */
    val elapsedMs: Long,
    /** 成功结果时间，不是跨页面缓存或备份字段。 */
    val loadedAtMillis: Long,
)
