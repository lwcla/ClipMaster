package com.cla.clip.master.ui.page.backup

/** 图会话中的媒体入口事实；不包含准备对象、权限 URI 或格式化字符串。 */
internal data class BackupMediaRelocationState(
    /** 仅成功恢复结果登记的任务身份，空值表示尚无可关联结果。 */
    val restoreTaskId: String? = null,
    /** 控制入口和完成按钮的轻量状态。 */
    val entryState: MediaRelocationEntryState = MediaRelocationEntryState.NotStarted,
    /** 同一任务最后一次终态，重新预估不清空。 */
    val lastTerminalSummary: MediaRelocationSummary? = null,
)

/** 无 IO 的状态归约边界，任务不匹配时保持原对象。 */
internal object BackupMediaRelocationStateReducer {
    /** 将事件转换为可稍后读取的事实状态；输入输出均不持久化。 */
    fun reduce(state: BackupMediaRelocationState, event: BackupMediaRelocationEvent): BackupMediaRelocationState {
        // 不重放旧恢复任务，也不以可空任务身份匹配事件。
        if (state.restoreTaskId != event.restoreTaskId) return state
        return when (event) {
            is BackupMediaRelocationEvent.Incomplete -> state.copy(entryState = MediaRelocationEntryState.Incomplete)
            is BackupMediaRelocationEvent.Running -> state.copy(entryState = MediaRelocationEntryState.Running)
            is BackupMediaRelocationEvent.Terminal -> state.copy(
                entryState = if (event.terminalSummary.type == MediaRelocationSummaryType.PermissionDenied) {
                    MediaRelocationEntryState.Incomplete
                } else {
                    MediaRelocationEntryState.Terminal
                },
                lastTerminalSummary = event.terminalSummary,
            )
            is BackupMediaRelocationEvent.Interrupted -> state.copy(
                entryState = MediaRelocationEntryState.Terminal,
                lastTerminalSummary = event.interruptedSummary,
            )
        }
    }
}
