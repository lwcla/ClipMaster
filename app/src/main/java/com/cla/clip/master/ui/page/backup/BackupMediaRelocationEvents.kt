package com.cla.clip.master.ui.page.backup

/** 当前图会话的媒体状态变化，只传任务身份和结构化摘要，不再由全局 SharedFlow 发送。 */
sealed class BackupMediaRelocationEvent(
    /** 只匹配同一完整恢复结果，不作为持久化任务凭据。 */
    open val restoreTaskId: String,
) {
    /** 当前准备、授权或待确认阶段，保留上一轮摘要。 */
    data class Incomplete(/** 当前恢复结果的任务身份。 */ override val restoreTaskId: String) : BackupMediaRelocationEvent(restoreTaskId)
    /** 正式扫描期间拦截恢复页完成操作。 */
    data class Running(/** 当前恢复结果的任务身份。 */ override val restoreTaskId: String) : BackupMediaRelocationEvent(restoreTaskId)
    /** 本轮关联形成结果；权限不足仍保留可重试入口。 */
    data class Terminal(
        /** 结果所属的恢复任务，不包含文件标识。 */
        override val restoreTaskId: String,
        /** 类型和数量摘要，不保存本地化字符串或媒体 URI。 */
        val terminalSummary: MediaRelocationSummary,
    ) : BackupMediaRelocationEvent(restoreTaskId)

    /** 目的地异常释放时同步解除 Running，保留已处理数量。 */
    data class Interrupted(
        /** 被中断关联所属的恢复任务。 */
        override val restoreTaskId: String,
        /** 中断类型和计数摘要，不含媒体实体。 */
        val interruptedSummary: MediaRelocationSummary,
    ) : BackupMediaRelocationEvent(restoreTaskId)
}

/** 固定的低敏事件分类，只用于日志检索。 */
val BackupMediaRelocationEvent.logCode: String
    get() = when (this) {
        is BackupMediaRelocationEvent.Incomplete -> "incomplete"
        is BackupMediaRelocationEvent.Running -> "running"
        is BackupMediaRelocationEvent.Terminal -> "terminal"
        is BackupMediaRelocationEvent.Interrupted -> "interrupted"
    }
