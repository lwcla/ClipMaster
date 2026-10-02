package com.cla.clip.master.ui.page.backup

import org.junit.Assert.*
import org.junit.Test

/** 保护媒体回显的状态归约，防止重试清空结果或中断后永久禁用完成。 */
class BackupMediaRelocationStateReducerTest {
    /** 错任务的事件不能改变当前摘要或入口状态。 */
    @Test
    fun mismatchedTaskDoesNotChangeState() {
        /** 当前任务身份独立于事件携带的旧身份。 */
        val state = BackupMediaRelocationState(restoreTaskId = "current")
        assertSame(state, BackupMediaRelocationStateReducer.reduce(state, BackupMediaRelocationEvent.Running("old")))
    }

    /** 再次预估和扫描保留同任务已有终态摘要，返回时仍能查看上次结果。 */
    @Test
    fun incompleteAndRunningKeepPreviousSummary() {
        /** 摘要只保留结构化类型和空计数，不提前格式化文案。 */
        val summary = MediaRelocationSummary(MediaRelocationSummaryType.Completed)
        /** 当前媒体入口事实，用于验证状态映射。 */
        val state = BackupMediaRelocationState("current", MediaRelocationEntryState.Terminal, summary)
        /** 本轮开始时分别验证预估和正式扫描回显。 */
        val incomplete = BackupMediaRelocationStateReducer.reduce(state, BackupMediaRelocationEvent.Incomplete("current"))
        /** 本轮扫描状态，用于验证保留上轮结果。 */
        val running = BackupMediaRelocationStateReducer.reduce(incomplete, BackupMediaRelocationEvent.Running("current"))
        assertEquals(MediaRelocationEntryState.Incomplete, incomplete.entryState)
        assertEquals(MediaRelocationEntryState.Running, running.entryState)
        assertSame(summary, incomplete.lastTerminalSummary)
        assertSame(summary, running.lastTerminalSummary)
    }

    /** 权限不足保留未完成入口，其余终态解除运行状态并显示结果。 */
    @Test
    fun terminalTypesMapToRetryableOrTerminalEntry() {
        /** 显式期望表独立于生产 mapper，防止只验证实现自身。 */
        val expectations = mapOf(
            MediaRelocationSummaryType.PermissionDenied to MediaRelocationEntryState.Incomplete,
            MediaRelocationSummaryType.Completed to MediaRelocationEntryState.Terminal,
            MediaRelocationSummaryType.NoWork to MediaRelocationEntryState.Terminal,
            MediaRelocationSummaryType.Failed to MediaRelocationEntryState.Terminal,
        )
        expectations.forEach { (type, expected) ->
            /** 每种摘要都从运行态开始，验证完成门禁能释放。 */
            val summary = MediaRelocationSummary(type)
            /** 归约后的状态，用于检查终态及摘要。 */
            val result = BackupMediaRelocationStateReducer.reduce(
                BackupMediaRelocationState("current", MediaRelocationEntryState.Running),
                BackupMediaRelocationEvent.Terminal("current", summary)
            )
            assertEquals(type.logCode, expected, result.entryState)
            assertSame(summary, result.lastTerminalSummary)
        }
    }

    /** VM 中断清理同步回显后，不再依赖已取消协程发送事件。 */
    @Test
    fun interruptionReleasesRunningEntry() {
        /** 运行阶段报告被映射为明确中断摘要。 */
        val summary = MediaRelocationSummary(MediaRelocationSummaryType.Interrupted)
        /** 归约后的状态，用于检查终态及摘要。 */
        val result = BackupMediaRelocationStateReducer.reduce(
            BackupMediaRelocationState("current", MediaRelocationEntryState.Running),
            BackupMediaRelocationEvent.Interrupted("current", summary)
        )
        assertEquals(MediaRelocationEntryState.Terminal, result.entryState)
        assertSame(summary, result.lastTerminalSummary)
    }
}
