package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.master.backup.diagnostics.BackupDiagnostics
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.utils.logE
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 每日生成入口，无网络约束，不读取 dirty，也不请求失败退避。 */
@HiltWorker
class BackupAutoWorker @AssistedInject internal constructor(
    /** 系统提供的应用上下文。 */
    @Assisted appContext: Context,
    /** 系统提供的周期执行参数。 */
    @Assisted params: WorkerParameters,
    /** 共用的生成和文件交接流程。 */
    private val coordinator: BackupExecutionCoordinator,
    /** 独立的低敏留证，不参与调度或备份结果。 */
    private val diagnostics: BackupDiagnostics,
) : CoroutineWorker(appContext, params) {
    /** 每次实际执行单独计时，中断在有限 IO 下留证后继续传播，不修改重试策略。 */
    override suspend fun doWork(): Result {
        /** 当前 Worker 身份与运行次数，排队时间不计入本轮实际耗时。 */
        /** 旧日周期无输入仍按自动处理，手动请求复用同一生成入口。 */
        val automatic = inputData.getString("source") != "manual"
        val trace = diagnostics.start(id.toString(), if (automatic) "auto" else "manual", runAttemptCount)
        trace.stage("waiting_task_gate")
        return try {
            coordinator.generate(automatic = automatic, diagnostic = trace)
            trace.event("worker_succeeded")
            Result.success()
        } catch (failure: Throwable) {
            // 系统停止时的留证不能随原协程取消丢失，完成后仍传播同一取消。
            withContext(NonCancellable + Dispatchers.IO) {
                trace.event(if (failure is CancellationException) "stopped" else "worker_failed",
                    diagnostics.stopReason(this@BackupAutoWorker), failure.javaClass.simpleName,
                    if (failure is CancellationException) "generation_interrupted" else failure.backupReasonCode())
            }
            if (failure is CancellationException) throw failure
            logE("BackupAutoWorker") { "每日备份结束 reasonCode=generation_failed type=${failure::class.simpleName}" }
            Result.failure()
        }
    }
}
