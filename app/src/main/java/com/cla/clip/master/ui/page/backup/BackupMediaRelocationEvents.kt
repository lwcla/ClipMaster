package com.cla.clip.master.ui.page.backup

import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份恢复 feature 内部的媒体关联事件流。
 *
 * 只用于独立媒体关联页向当前恢复页回显入口状态和结构化摘要；`replay = 0`，不承载事实状态，也不作为通用 EventBus。
 */
@Singleton
class BackupMediaRelocationEvents @Inject constructor() {
    companion object {
        private const val TAG = "BackupMediaRelocationEvents"
    }

    private val _events = MutableSharedFlow<BackupMediaRelocationEvent>(replay = 0, extraBufferCapacity = 1)

    val events: SharedFlow<BackupMediaRelocationEvent> = _events.asSharedFlow()

    suspend fun emitIncomplete(restoreTaskId: String) {
        emit(BackupMediaRelocationEvent.Incomplete(restoreTaskId))
    }

    suspend fun emitRunning(restoreTaskId: String) {
        emit(BackupMediaRelocationEvent.Running(restoreTaskId))
    }

    suspend fun emitTerminal(restoreTaskId: String, summary: MediaRelocationSummary) {
        emit(BackupMediaRelocationEvent.Terminal(restoreTaskId, summary))
    }

    fun tryEmitInterrupted(restoreTaskId: String, summary: MediaRelocationSummary): Boolean {
        val event = BackupMediaRelocationEvent.Interrupted(restoreTaskId, summary)
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            logW(TAG) {
                "媒体关联中断事件发送失败 restoreTaskId=$restoreTaskId eventType=${event.logCode} " +
                    "summaryType=${summary.type.logCode} reasonCode=event_emit_failed"
            }
        } else {
            logD(TAG) {
                "媒体关联中断事件已发送 restoreTaskId=$restoreTaskId eventType=${event.logCode} " +
                    "summaryType=${summary.type.logCode} relocated=${summary.totalRelocated}"
            }
        }
        return emitted
    }

    private suspend fun emit(event: BackupMediaRelocationEvent) {
        _events.emit(event)
        logD(TAG) {
            "媒体关联事件已发送 restoreTaskId=${event.restoreTaskId} eventType=${event.logCode} " +
                "summaryType=${event.summary?.type?.logCode ?: "none"} relocated=${event.summary?.totalRelocated ?: 0}"
        }
    }
}

sealed class BackupMediaRelocationEvent(
    open val restoreTaskId: String,
) {
    data class Incomplete(override val restoreTaskId: String) : BackupMediaRelocationEvent(restoreTaskId)
    data class Running(override val restoreTaskId: String) : BackupMediaRelocationEvent(restoreTaskId)
    data class Terminal(
        override val restoreTaskId: String,
        val terminalSummary: MediaRelocationSummary,
    ) : BackupMediaRelocationEvent(restoreTaskId)

    data class Interrupted(
        override val restoreTaskId: String,
        val interruptedSummary: MediaRelocationSummary,
    ) : BackupMediaRelocationEvent(restoreTaskId)
}

val BackupMediaRelocationEvent.logCode: String
    get() = when (this) {
        is BackupMediaRelocationEvent.Incomplete -> "incomplete"
        is BackupMediaRelocationEvent.Running -> "running"
        is BackupMediaRelocationEvent.Terminal -> "terminal"
        is BackupMediaRelocationEvent.Interrupted -> "interrupted"
    }

val BackupMediaRelocationEvent.summary: MediaRelocationSummary?
    get() = when (this) {
        is BackupMediaRelocationEvent.Terminal -> terminalSummary
        is BackupMediaRelocationEvent.Interrupted -> interruptedSummary
        is BackupMediaRelocationEvent.Incomplete,
        is BackupMediaRelocationEvent.Running -> null
    }
