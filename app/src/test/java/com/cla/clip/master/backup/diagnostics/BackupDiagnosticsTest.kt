package com.cla.clip.master.backup.diagnostics

import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Test

/** 保护停止原因分类、即时日志隐私与 trace 耗时边界，均使用合成事件。 */
class BackupDiagnosticsTest {
    /** 抢占与系统处理都是平台已知暂停，漏项会使系统重调度的完整包被失败终态挡住。 */
    @Test fun preemptionAndSystemProcessingRemainResumable() {
        assertTrue("系统抢占应保留同包重执行机会", BackupStopReasonMapper.canResume(WorkInfo.STOP_REASON_PREEMPT))
        assertTrue("系统处理应保留同包重执行机会", BackupStopReasonMapper.canResume(WorkInfo.STOP_REASON_SYSTEM_PROCESSING))
    }

    /** 可恢复判定不允许未知/用户取消，系统时限、配额和后台设备约束则等待调度。 */
    @Test fun resumableStopsRequireActualSystemEvidence() {
        // stop 为真实平台常量，JVM 只保护分类，不证明 Android 会何时调度。
        for (stop in listOf(WorkInfo.STOP_REASON_DEVICE_STATE, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY, WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)) assertTrue(BackupStopReasonMapper.canResume(stop))
        for (stop in listOf(WorkInfo.STOP_REASON_USER, WorkInfo.STOP_REASON_CANCELLED_BY_APP,
            WorkInfo.STOP_REASON_UNKNOWN, WorkInfo.STOP_REASON_NOT_STOPPED, 9999)) assertFalse(BackupStopReasonMapper.canResume(stop))
    }
    /** 超时、配额与连接约束必须区分，不能全部显示未知错误。 */
    @Test fun distinguishesKnownStopsAndKeepsUnknownInterruption() {
        assertEquals("upload_interrupted_timeout", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_TIMEOUT))
        assertEquals("upload_interrupted_quota", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_QUOTA))
        assertEquals("upload_interrupted_network", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertEquals("upload_interrupted_device", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION))
        assertEquals("upload_interrupted_device", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_DEVICE_STATE))
        assertEquals("upload_interrupted_device", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_APP_STANDBY))
        assertEquals("upload_interrupted_cancelled", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertEquals("upload_interrupted_cancelled", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_USER))
        assertEquals("upload_interrupted", BackupStopReasonMapper.interruptionCode(WorkInfo.STOP_REASON_NOT_STOPPED))
        assertEquals("upload_interrupted", BackupStopReasonMapper.interruptionCode(9999))
    }

    /** 来源、身份和耗时可对照，但非法地址/自由文本必须整体脱敏。 */
    @Test fun encodesSafeFieldsAndRejectsFreeText() {
        /** 固定合成事件，没有真实用户内容。 */
        val record = BackupDiagnosticRecord(1, "publishing", "stopped", "synthetic-work", "daily-synthetic", "auto", 15, 2, 3, "IOException", "upload_interrupted_timeout")
        /** 单行事件应保留可诊断字段。 */
        val line = BackupDiagnosticPolicy.encode(record)
        assertTrue(line.contains("source=auto"))
        assertTrue(line.contains("elapsedMs=15"))
        assertTrue(line.contains("stopReason=3"))
        assertTrue(line.contains("phase=publishing"))
        assertTrue(line.contains("reasonCode=upload_interrupted_timeout"))
        assertEquals(1, line.lines().size)
        /** 地址和换行只用于攻击边界合成数据。 */
        val unsafe = BackupDiagnosticPolicy.encode(record.copy(errorType = "https://example.invalid?secret=synthetic\nforged", artifactId = "../unsafe", reasonCode = "https://example.invalid"))
        assertFalse(unsafe.contains("example.invalid"))
        assertFalse(unsafe.contains("synthetic\n"))
        assertFalse(unsafe.contains("../unsafe"))
        assertTrue(unsafe.contains("errorType=redacted"))
        assertTrue(unsafe.contains("reasonCode=redacted"))
    }

    /** 墙钟跳变不能变成超时证据，停止事件必须保留实际最后阶段。 */
    @Test fun traceKeepsPhaseAndUsesMonotonicExecutionTime() {
        /** 合成单调时间与墙钟可独立推进，不依赖真实系统时钟。 */
        var elapsed = 100L
        /** 墙钟值只用来串联日志，允许在运行中跳变。 */
        var wall = 1000L
        /** 捕获结构化事件，不访问真实 Android 文件。 */
        val records = mutableListOf<BackupDiagnosticRecord>()
        /** 同轮 trace 从 Worker 入口起算，锁等待也包含在内。 */
        val trace = BackupDiagnosticTrace({ records += it }, { elapsed }, { wall }, "work", "auto", 2, "artifact")
        trace.stage("waiting_task_gate")
        elapsed = 150
        wall = 9_999_999
        trace.stage("reading_back_temporary_zip")
        trace.event("stopped", 3, "CancellationException", "upload_interrupted_timeout")
        assertEquals(50L, records.last().elapsedMs)
        assertEquals("reading_back_temporary_zip", records.last().phase)
        assertEquals(3, records.last().stopReason)
        assertEquals("upload_interrupted_timeout", records.last().reasonCode)
    }
}
