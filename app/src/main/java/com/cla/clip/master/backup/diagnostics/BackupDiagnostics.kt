package com.cla.clip.master.backup.diagnostics

import android.os.SystemClock
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.logE
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份即时诊断适配器，复用统一 Logcat 门控，不保存本地日志或访问业务数据。
 *
 * 无参数注入构造保留 Worker/协调器的既有依赖；单例自身不持有 Context、文件或任务状态，
 * 每次 start 都创建独立 trace，阶段和停止原因仅在系统允许输出时进入有限 Logcat 缓冲。
 */
@Singleton
internal class BackupDiagnostics @Inject constructor() {
    /** 创建一轮独立的即时诊断上下文；不读取设置、任务队列、日志文件或业务数据库。 */
    fun start(workId: String = "", source: String, attempt: Int = 0, artifactId: String = ""): BackupDiagnosticTrace =
        BackupDiagnosticTrace(::record, SystemClock::elapsedRealtime, System::currentTimeMillis,
            workId, source, attempt, artifactId).also { it.event("started") }

    /** Android 12 起读取当次停止证据，旧平台明确未知，不从执行时长推算原因。 */
    fun stopReason(worker: ListenableWorker): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason else WorkInfo.STOP_REASON_UNKNOWN

    /** 根据既有事件分类即时输出白名单字段；不读写文件，也不改变备份业务结果。 */
    fun record(record: BackupDiagnosticRecord) {
        // 保持中断、失败与普通阶段的既有级别，字段仅在统一日志门控允许时格式化。
        when (record.event) {
            "stopped", "interrupted" -> logW("BackupDiagnostics") { "备份中断 ${BackupDiagnosticPolicy.encode(record)}" }
            "worker_failed", "generation_failed", "upload_failed" -> logE("BackupDiagnostics") { "备份异常 ${BackupDiagnosticPolicy.encode(record)}" }
            else -> logI("BackupDiagnostics") { "备份阶段 ${BackupDiagnosticPolicy.encode(record)}" }
        }
    }
}
