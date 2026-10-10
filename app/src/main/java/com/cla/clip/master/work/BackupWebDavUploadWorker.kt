package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.master.backup.diagnostics.BackupDiagnostics
import com.cla.clip.master.backup.diagnostics.BackupStopReasonMapper
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.utils.logE
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 上传已生成的同一份 ZIP；该入口没有数据库导出能力。 */
@HiltWorker
class BackupWebDavUploadWorker @AssistedInject internal constructor(
    /** 系统应用上下文。 */
    @Assisted appContext: Context,
    /** 包含低敏文件身份的系统参数。 */
    @Assisted params: WorkerParameters,
    /** 上传流程协作者，负责身份验证和终态清理。 */
    private val coordinator: BackupExecutionCoordinator,
    /** 低敏即时诊断遵循统一 Logcat 门控，不再持久化历史事件。 */
    private val diagnostics: BackupDiagnostics,
) : CoroutineWorker(appContext, params) {
    /** 失败不安排重试；记录真实执行跨度与停止原因，不将身份缺失退出等同上传成功。 */
    override suspend fun doWork(): Result {
        /** 参数仅包含文件身份，没有路径、配置或密码。 */
        val artifactId = inputData.getString("round_id")
        /** 上传来源随后从当前身份读取，不从未知历史任务猜测。 */
        val trace = diagnostics.start(id.toString(), "unknown", runAttemptCount, artifactId.orEmpty())
        if (artifactId == null) {
            // 原有缺参失败不再静默，仍不生成或上传任何包。
            trace.stage("artifact_input_missing")
            trace.event("worker_failed", reasonCode = "artifact_input_missing")
            return Result.success()
        }
        trace.stage("checking_upload_round")
        return try {
            // 在中断捕获时读取当次停止原因，不从历史 WorkSpec 推断。
            coordinator.upload(artifactId, trace) { diagnostics.stopReason(this) }
            trace.event("worker_succeeded")
            Result.success()
        } catch (failure: Throwable) {
            // 取消后保留既有边界即时输出停止事件，不写日志文件；原取消继续传播，绝不安排新的上传。
            withContext(NonCancellable + Dispatchers.IO) {
                /** 本条结束记录只读取一次系统停止证据，兼顾尚未进入协调器的任务锁等待中断。 */
                val stopReason = diagnostics.stopReason(this@BackupWebDavUploadWorker)
                trace.event(if (failure is CancellationException) "stopped" else "worker_failed",
                    stopReason, failure.javaClass.simpleName,
                    if (failure is CancellationException) BackupStopReasonMapper.interruptionCode(stopReason) else failure.backupReasonCode())
            }
            if (failure is CancellationException) throw failure
            logE("BackupWebDavUploadWorker") { "上传任务结束 reasonCode=upload_failed type=${failure::class.simpleName}" }
            Result.failure()
        }
    }
}
