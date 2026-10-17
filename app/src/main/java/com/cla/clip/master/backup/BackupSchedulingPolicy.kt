package com.cla.clip.master.backup

/** 生成频率与上传等待策略；纯规则独立于 WorkManager 和数据库。 */
internal object BackupSchedulingPolicy {
    /** 本地快照与正式目录发布永远不要求网络。 */
    const val generationRequiresNetwork = false
    /** 滚动日周期，具体执行时间由系统调度。 */
    const val intervalHours = 24L
    /** 自动与手动上传均等待可用网络，不限定 Wi-Fi；本地目标保持独立。 */
    const val uploadRequiresNetwork = true
    /** 指数退避基础一分钟，实际执行受系统调度约束，不承诺准点。 */
    const val uploadBackoffSeconds = 60L
    /** 失败及运行中断不得在启动时复活，只有尚未开始上传的包可以修复入队。 */
    fun canSchedule(phase: String, workFinished: Boolean = false): Boolean = !workFinished && (phase == "ready" || phase == "queued")

    /** 仅系统已给出执行机会时使用，重执行规则由回归保护；不改变本地入队策略。 */
    fun canExecuteWebDavUpload(phase: String, workFinished: Boolean = false): Boolean =
        !workFinished && phase in setOf("ready", "queued", "uploading")
}
