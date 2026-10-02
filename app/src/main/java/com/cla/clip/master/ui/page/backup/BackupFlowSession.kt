package com.cla.clip.master.ui.page.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import java.util.UUID

/** 当前导航组唯一的内存会话，不依赖 Android 或全局注入作用域。 */
internal class BackupFlowSession {
    /** 低敏会话身份仅用于本机日志，不进入导航、保存状态或备份协议。 */
    val sessionId: String = UUID.randomUUID().toString().take(8)
    /** 已接管请求的目的地所有权，恢复页退出前拒绝替换。 */
    private var activeRequestId: Long? = null
    /** 转场释放 VM 前先关闭写入，避免迟到任务污染下一轮。 */
    private var closed = false
    /** 待接管恢复请求，不向保存状态或备份协议泄露 URI。 */
    private val pendingRequest = MutableStateFlow<BackupRestoreRequest?>(null)
    /** 媒体关联回显事实，即使 UI 无订阅者也保留最新值。 */
    private val media = MutableStateFlow(BackupMediaRelocationState())
    /** 页面可观察的单槽请求。 */
    val requests = pendingRequest.asStateFlow()
    /** 页面可观察的媒体回显。 */
    val mediaState = media.asStateFlow()
    /** 接受一个恢复目标，忙碌或关闭时拒绝。 */
    @Synchronized
    fun submitRequest(request: BackupRestoreRequest): Boolean {
        if (closed || activeRequestId != null || pendingRequest.value != null) {
            logW("BackupFlowSession") { "恢复请求已拒绝 sessionId=$sessionId requestId=${request.requestId} reasonCode=${if (closed) "session_closed" else "restore_busy"}" }
            return false
        }
        pendingRequest.value = request
        logD("BackupFlowSession") { "恢复请求已接受 sessionId=$sessionId requestId=${request.requestId} sourceType=${request.sourceLogCode}" }
        return true
    }
    /** Reading 建立后确认接管，并清除对应待接管槽位。 */
    @Synchronized
    fun acknowledgeRequest(requestId: Long): Boolean {
        if (closed || activeRequestId != null || pendingRequest.value?.requestId != requestId) return false
        activeRequestId = requestId
        pendingRequest.value = null
        logD("BackupFlowSession") { "恢复请求已接管 sessionId=$sessionId requestId=$requestId" }
        return true
    }
    /** 导航失败只清理仍未接管的同一个请求。 */
    @Synchronized
    fun clearPendingRequest(requestId: Long): Boolean {
        if (pendingRequest.value?.requestId != requestId) return false
        pendingRequest.value = null
        logD("BackupFlowSession") { "未接管请求已清理 sessionId=$sessionId requestId=$requestId" }
        return true
    }
    /** 完整恢复结果登记可供媒体关联的身份。 */
    @Synchronized
    fun registerRestoreTask(requestId: Long, taskId: String): Boolean {
        if (closed || activeRequestId != requestId) return false
        if (media.value.restoreTaskId != taskId) media.value = BackupMediaRelocationState(restoreTaskId = taskId)
        return true
    }
    /** 当前恢复目的地结束后释放所有权，旧 id 无法清除新流程。 */
    @Synchronized
    fun finishRestore(requestId: Long) {
        if (activeRequestId != requestId) return
        activeRequestId = null
        media.value = BackupMediaRelocationState()
        logD("BackupFlowSession") { "恢复会话所有权已释放 sessionId=$sessionId requestId=$requestId" }
    }
    /** 媒体页每次启动及回调复核会话身份，运行中仍允许写回。 */
    @Synchronized
    fun matchesRestoreTask(taskId: String): Boolean = !closed && activeRequestId != null && media.value.restoreTaskId == taskId
    /** 媒体导航另加运行状态门禁。 */
    @Synchronized
    fun canOpenMedia(taskId: String): Boolean = matchesRestoreTask(taskId) && !media.value.entryState.isRunning
    /** 页面退出门禁直接读取同步事实，不能使用重组前的展示状态。 */
    @Synchronized
    fun canLeaveMedia(taskId: String): Boolean = canOpenMedia(taskId)
    /** 同步落到状态，不能依赖 UI collector 才保存终态。 */
    @Synchronized
    fun publishMediaEvent(event: BackupMediaRelocationEvent): Boolean {
        if (!matchesRestoreTask(event.restoreTaskId)) {
            logW("BackupFlowSession") { "媒体回显已拒绝 sessionId=$sessionId restoreTaskId=${event.restoreTaskId} eventType=${event.logCode} reasonCode=${if (closed) "session_closed" else "restore_task_mismatch"}" }
            return false
        }
        /** 前后状态只用于低敏诊断，不打印任务报告对象。 */
        val previous = media.value
        /** 归约结果先写入内存事实，再允许任意 collector 观察。 */
        val next = BackupMediaRelocationStateReducer.reduce(previous, event)
        media.value = next
        logD("BackupFlowSession") { "媒体回显已保存 sessionId=$sessionId restoreTaskId=${event.restoreTaskId} eventType=${event.logCode} fromState=${previous.entryState.logCode} toState=${next.entryState.logCode} summaryType=${next.lastTerminalSummary?.type?.logCode ?: "none"}" }
        return true
    }
    /** 整组退出立即拒绝旧任务写入；只有首次关闭返回 true，供导航领取一次弹栈许可。 */
    @Synchronized
    fun close(): Boolean {
        if (closed) return false
        logD("BackupFlowSession") { "导航组会话已关闭 sessionId=$sessionId hasPendingRequest=${pendingRequest.value != null} hasActiveRestore=${activeRequestId != null}" }
        closed = true
        activeRequestId = null
        pendingRequest.value = null
        media.value = BackupMediaRelocationState()
        return true
    }
}
