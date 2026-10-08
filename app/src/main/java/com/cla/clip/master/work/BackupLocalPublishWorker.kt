package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.master.backup.diagnostics.BackupDiagnostics
import com.cla.clip.base.general.backup.backupReasonCode
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*

/** 独立本地保存入口，不等待云端，也不重新读取业务数据库。 */
@HiltWorker
class BackupLocalPublishWorker @AssistedInject internal constructor(
    /** 系统提供的应用上下文。 */
    @Assisted appContext: Context,
    /** 系统一次性任务参数。 */
    @Assisted params: WorkerParameters,
    /** 共用轮次检查和分目标结果事务。 */
    private val coordinator: BackupExecutionCoordinator,
    /** 低敏阶段记录，不能保存 URI 或内容。 */
    private val diagnostics: BackupDiagnostics,
) : CoroutineWorker(appContext, params) {
    /** 失败不主动重试；缺少新轮次输入的旧请求安全退出。 */
    override suspend fun doWork(): Result {
        /** 输入仅包含任务身份，不包含授权 URI。 */
        val roundId = inputData.getString("round_id") ?: return Result.success()
        /** 与生成、云端共享轮次身份，实际运行耗时单独计量。 */
        val trace = diagnostics.start(id.toString(), "unknown", runAttemptCount, roundId)
        return try {
            coordinator.publishLocal(roundId, trace)
            trace.event("worker_succeeded")
            Result.success()
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                trace.event(if (failure is CancellationException) "stopped" else "worker_failed",
                    diagnostics.stopReason(this@BackupLocalPublishWorker), failure.javaClass.simpleName,
                    if (failure is CancellationException) "local_interrupted" else failure.backupReasonCode())
            }
            if (failure is CancellationException) throw failure
            Result.failure()
        }
    }
}
