package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.backup.RemoteBackupFile
import org.junit.Assert.*
import org.junit.Test

/** 保护图会话的请求所有权、迟到回调和无订阅者时的结果保存契约。 */
class BackupFlowSessionTest {
    /** 待接管请求不能被第二次点击覆盖，接管后同一恢复目的地仍独占会话。 */
    @Test
    fun pendingAndClaimedRequestsRejectReplacement() {
        /** 当前会话及两个合成目标不依赖真实网络、文件或 Android URI。 */
        val session = BackupFlowSession()
        /** 前一请求或会话，用于验证旧身份不会污染后续流程。 */
        val first = request()
        /** 后一请求或会话，独立保存新一轮目标。 */
        val second = request()
        assertTrue(session.submitRequest(first))
        assertFalse(session.submitRequest(second))
        assertSame(first, session.requests.value)
        assertTrue(session.acknowledgeRequest(first.requestId))
        assertNull(session.requests.value)
        assertFalse(session.acknowledgeRequest(first.requestId))
        assertFalse(session.submitRequest(second))
        session.finishRestore(first.requestId)
        assertTrue(session.submitRequest(second))
    }

    /** 旧确认或旧清理不能移除新恢复流程，避免转场期间旧 VM 干扰新 VM。 */
    @Test
    fun staleAcknowledgementAndCleanupCannotClearNewRequest() {
        /** 两个合成请求分别代表先后两次进入恢复页。 */
        val session = BackupFlowSession()
        /** 前一请求或会话，用于验证旧身份不会污染后续流程。 */
        val first = request()
        /** 后一请求或会话，独立保存新一轮目标。 */
        val second = request()
        assertTrue(session.submitRequest(first))
        assertFalse(session.acknowledgeRequest(second.requestId))
        assertFalse(session.clearPendingRequest(second.requestId))
        assertTrue(session.acknowledgeRequest(first.requestId))
        session.finishRestore(first.requestId)
        assertTrue(session.submitRequest(second))
        session.finishRestore(first.requestId)
        assertSame(second, session.requests.value)
    }

    /** 图会话之间无共享槽位，同名任务的迟到回调也不能跨实例污染。 */
    @Test
    fun sessionsKeepRequestsAndResultsIsolated() {
        /** 两个实例代表两次进入导航组，目标及摘要均为合成值。 */
        val first = readySession()
        /** 后一请求或会话，独立保存新一轮目标。 */
        val second = BackupFlowSession()
        assertTrue(first.publishMediaEvent(BackupMediaRelocationEvent.Running("restore-test")))
        assertNull(second.mediaState.value.restoreTaskId)
        assertEquals(MediaRelocationEntryState.NotStarted, second.mediaState.value.entryState)
        assertFalse(second.publishMediaEvent(BackupMediaRelocationEvent.Running("restore-test")))
    }

    /** 终态没有 UI collector 也必须保存，稍后返回恢复页能直接读取。 */
    @Test
    fun terminalResultSurvivesWithoutSubscribers() {
        /** 空报告足够验证状态保存，不模拟真实扫描算法。 */
        val session = readySession()
        /** 结构化合成终态摘要，用于验证回显保存。 */
        val summary = MediaRelocationSummary(MediaRelocationSummaryType.Completed)
        assertTrue(session.publishMediaEvent(BackupMediaRelocationEvent.Terminal("restore-test", summary)))
        assertEquals(MediaRelocationEntryState.Terminal, session.mediaState.value.entryState)
        assertSame(summary, session.mediaState.value.lastTerminalSummary)
        assertTrue(session.canOpenMedia("restore-test"))
    }

    /** 正式扫描期间禁止重复进入，任务身份不匹配时也禁止打开。 */
    @Test
    fun runningAndWrongTaskRejectMediaNavigation() {
        /** 当前会话已接管恢复并登记结果身份。 */
        val session = readySession()
        assertFalse(session.canOpenMedia("old-task"))
        assertTrue(session.publishMediaEvent(BackupMediaRelocationEvent.Running("restore-test")))
        assertFalse(session.canOpenMedia("restore-test"))
        assertTrue(session.matchesRestoreTask("restore-test"))
    }

    /** 关闭立即撤销写权限，不依赖父图 VM 稍后 onCleared 的时机。 */
    @Test
    fun closedSessionRejectsLateRequestsAndEvents() {
        /** 旧会话结束后仍可能有权限/协程回调到达。 */
        val session = readySession()
        session.close()
        assertFalse(session.submitRequest(request()))
        assertFalse(session.publishMediaEvent(BackupMediaRelocationEvent.Running("restore-test")))
        assertFalse(session.matchesRestoreTask("restore-test"))
        assertFalse(session.canOpenMedia("restore-test"))
        assertNull(session.requests.value)
        assertNull(session.mediaState.value.restoreTaskId)
    }

    /** 扫描启动与界面重组之间仍必须同步拒绝退出，终态后重新允许普通返回。 */
    @Test
    fun mediaExitChecksLatestStateBeforeUiCollectorUpdates() {
        /** 保存旧展示状态，模拟 Compose 尚未观察到刚写入的 Running。 */
        val session = readySession()
        /** 界面尚未收集更新时的旧值，模拟重组延迟。 */
        val staleDisplayState = session.mediaState.value
        assertTrue(session.canLeaveMedia("restore-test"))
        session.publishMediaEvent(BackupMediaRelocationEvent.Running("restore-test"))
        assertFalse(staleDisplayState.entryState.isRunning)
        assertFalse(session.canLeaveMedia("restore-test"))
        session.publishMediaEvent(BackupMediaRelocationEvent.Interrupted("restore-test", MediaRelocationSummary(MediaRelocationSummaryType.Interrupted)))
        assertTrue(session.canLeaveMedia("restore-test"))
        session.close()
        assertFalse(session.canLeaveMedia("restore-test"))
    }

    /** 退出转场期间重复完成只允许第一次领取弹栈许可，不能继续弹出组外页面。 */
    @Test
    fun groupClosePermissionCanOnlyBeClaimedOnce() {
        /** 同一会话模拟连续两次旧页面完成回调。 */
        val session = readySession()
        assertTrue(session.close())
        assertFalse(session.close())
    }

    /** 开始下一次恢复前清除旧摘要，旧任务登记不能覆盖新的活动请求。 */
    @Test
    fun nextRestoreClearsSummaryAndRejectsOldOwner() {
        /** 同组内先退出恢复页，再选择另一备份。 */
        val session = BackupFlowSession()
        /** 前一请求或会话，用于验证旧身份不会污染后续流程。 */
        val first = request()
        /** 后一请求或会话，独立保存新一轮目标。 */
        val second = request()
        session.submitRequest(first)
        session.acknowledgeRequest(first.requestId)
        assertTrue(session.registerRestoreTask(first.requestId, "first-task"))
        session.publishMediaEvent(BackupMediaRelocationEvent.Terminal("first-task", MediaRelocationSummary(MediaRelocationSummaryType.Completed)))
        session.finishRestore(first.requestId)
        session.submitRequest(second)
        session.acknowledgeRequest(second.requestId)
        assertFalse(session.registerRestoreTask(first.requestId, "first-task"))
        assertTrue(session.registerRestoreTask(second.requestId, "second-task"))
        assertNull(session.mediaState.value.lastTerminalSummary)
        assertFalse(session.publishMediaEvent(BackupMediaRelocationEvent.Running("first-task")))
    }

    /** 构造可进入关联阶段的会话，所有状态通过真实请求接管接口建立。 */
    private fun readySession(): BackupFlowSession {
        /** 合成请求只携带无效的测试文件名，不访问服务端。 */
        val session = BackupFlowSession()
        /** 合成恢复目标，不访问真实文件或服务端。 */
        val request = request()
        assertTrue(session.submitRequest(request))
        assertTrue(session.acknowledgeRequest(request.requestId))
        assertTrue(session.registerRestoreTask(request.requestId, "restore-test"))
        return session
    }

    /** 合成远端目标用于纯请求测试，不含真实地址、账号或授权 URI。 */
    private fun request(): BackupRestoreRequest = BackupRestoreRequest.WebDav(
        RemoteBackupFile("synthetic.zip", "synthetic.zip", null, null)
    )
}
