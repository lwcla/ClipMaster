package com.cla.clip.master.backup.diagnostics

import androidx.work.WorkInfo
import java.io.IOException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

/** 保护后台停止证据、限量与隐私边界，均使用合成事件。 */
class BackupDiagnosticsTest {
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

    /** 新事件保留，裁剪只删除最旧完整行。 */
    @Test fun retainsLatestCompleteEventsWithinBothLimits() {
        /** 短合成事件用来验证条数边界。 */
        val many = (0 until 205).joinToString("\n") { "event_$it" }
        /** 最新事件不能被旧历史挤掉。 */
        val capped = BackupDiagnosticPolicy.append(many, "latest")
        assertEquals(200, capped.trimEnd().lines().size)
        assertFalse(capped.contains("event_0\n"))
        assertTrue(capped.endsWith("latest\n"))
        /** 大的合成行用于验证真实 UTF-8 字节上限。 */
        val large = (0 until 190).joinToString("\n") { "中".repeat(250) }
        /** 字节裁剪仍必须保留最新完整事件。 */
        val bounded = BackupDiagnosticPolicy.append(large, "latest")
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= BackupDiagnosticPolicy.maxBytes)
        assertTrue(bounded.endsWith("latest\n"))
    }

    /** 无新业务规则，只验证诊断读写故障不会向业务传播。 */
    @Test fun diagnosticIoFailureCannotEscapeToBackup() {
        /** 合成异常不包含用户文件信息。 */
        val journal = BackupDiagnosticJournal(read = { throw IOException("synthetic") }, write = { fail("读取失败不应写入") })
        assertFalse(journal.record(BackupDiagnosticRecord(1, "upload")))
        /** 写入失败同样只返回留证失败。 */
        val failedWrite = BackupDiagnosticJournal(read = { "" }, write = { throw IOException("synthetic") })
        assertFalse(failedWrite.record(BackupDiagnosticRecord(1, "upload")))
    }

    /** 后续事件保留前次停止证据，不由 Worker 成功反转上传结论。 */
    @Test fun laterWorkerSuccessRetainsEarlierStopEvidence() {
        /** 内存文件替身避免真实时钟和磁盘依赖。 */
        var saved = ""
        /** 诊断只负责追加，不参与业务结果存储。 */
        val journal = BackupDiagnosticJournal(read = { saved }, write = { saved = it })
        assertTrue(journal.record(BackupDiagnosticRecord(1, "upload", "stopped", stopReason = 3, reasonCode = "upload_interrupted_timeout")))
        assertTrue(journal.record(BackupDiagnosticRecord(2, "identity_missing", "worker_succeeded")))
        assertTrue(saved.contains("event=stopped"))
        assertTrue(saved.contains("event=worker_succeeded"))
        assertTrue(saved.contains("reasonCode=upload_interrupted_timeout"))
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

    /** 两个 Worker 同时写入时不能丢失其中一方的阶段。 */
    @Test fun concurrentExecutionsCannotOverwriteEachOthersEvents() {
        /** 文件替身只在 journal 的受保护读写中访问，结束后通过 join 建立可见性。 */
        var saved = ""
        /** 同一安装共用一份日志，模拟生成与上传两轮同时留证。 */
        val journal = BackupDiagnosticJournal(read = { saved }, write = { saved = it })
        /** 起跑门让两个执行线程都就绪后再开始，不依赖 sleep 或真实时钟。 */
        val start = CountDownLatch(1)
        /** 线程只提交有限合成事件，业务身份无真实用户信息。 */
        val threads = (0..1).map { worker ->
            Thread {
                start.await()
                repeat(20) { journal.record(BackupDiagnosticRecord(1, "upload", workId = "work-$worker")) }
            }.also(Thread::start)
        }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(40, saved.trimEnd().lines().size)
        assertEquals(20, saved.lineSequence().count { it.contains("workId=work-0 ") })
        assertEquals(20, saved.lineSequence().count { it.contains("workId=work-1 ") })
    }
}
