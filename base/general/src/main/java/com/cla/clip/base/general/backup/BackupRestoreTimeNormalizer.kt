package com.cla.clip.base.general.backup

/**
 * 备份恢复时间归一化器。
 *
 * 只处理剪贴记录这类用户可见时间轴字段：当远端备份来自系统时间偏快的设备时，先按 manifest 创建时间和本机恢复时间的
 * 差值整体平移，再把仍落在未来的时间压到恢复开始时间，避免列表长期显示“现在”并压过用户后续新复制的数据。
 */
internal class BackupRestoreTimeNormalizer(
    /** 当前设备开始执行恢复的时间，单位毫秒。 */
    private val restoreStartedAt: Long,
    /** 备份 manifest 中记录的创建时间，单位毫秒。 */
    manifestCreatedAt: Long,
) {
    /** 远端备份相对当前设备的正向时钟偏移；非正数表示无需整体平移。 */
    val clockSkewMillis: Long = (manifestCreatedAt - restoreStartedAt).coerceAtLeast(0L)

    /** 用于本地较新状态保护的备份创建时间，避免未来 manifest 误压过本机状态。 */
    val normalizedManifestCreatedAt: Long = manifestCreatedAt
        .takeIf { it <= restoreStartedAt }
        ?: restoreStartedAt

    /** 被归一化的时间字段数量，用于恢复完成后输出低敏诊断摘要。 */
    var normalizedFieldCount: Int = 0
        private set

    /**
     * 归一化用户可见时间字段。
     *
     * `0` 表示未置顶、未折叠或未删除，必须原样保留；正数时间会先扣除远端时钟偏移，再限制到恢复开始时间以内。
     */
    fun normalizeUserTime(value: Long): Long {
        if (value <= 0L) return value
        val shifted = (value - clockSkewMillis).coerceAtLeast(1L)
        val normalized = shifted.coerceAtMost(restoreStartedAt)
        if (normalized != value) normalizedFieldCount += 1
        return normalized
    }
}
