package com.cla.clip.master.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cla.clip.master.backup.BackupExecutionCoordinator
import com.cla.clip.master.backup.BackupTargetPublicationResult
import com.cla.clip.master.backup.upload.BackupRetryPersistenceFailure
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    /** 只有协调器已保存等待才向系统请求重试；身份跳过不能等同上传成功。 */
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
        return executeUpload(upload = {
            // 在中断捕获时读取当次停止原因，不从历史 WorkSpec 推断。
            coordinator.upload(artifactId, trace) { diagnostics.stopReason(this) }
        }, onResult = { outcome ->
            // 排队不输出结束事件，实际成功仍以协调器条件保存事件为准。
            trace.event(if (outcome is BackupTargetPublicationResult.RetryScheduled) "worker_retry" else "worker_finished")
        }, onFailure = { failure ->
            // 取消仍传播，系统暂停保留等待；存储故障独立于网络错误。
            withContext(NonCancellable + Dispatchers.IO) {
                /** 本条结束记录只读取一次系统停止证据，兼顾尚未进入协调器的任务锁等待中断。 */
                val stopReason = diagnostics.stopReason(this@BackupWebDavUploadWorker)
                trace.event(if (failure is CancellationException) "stopped" else "worker_failed",
                    stopReason, failure.javaClass.simpleName,
                    when (failure) {
                        is CancellationException -> BackupStopReasonMapper.interruptionCode(stopReason)
                        is BackupRetryPersistenceFailure -> "runtime_store_failed"
                        else -> failure.backupReasonCode()
                    })
            }
            if (failure !is CancellationException)
                logE("BackupWebDavUploadWorker") { "上传任务结束 reasonCode=upload_failed type=${failure::class.simpleName}" }
        })
    }

    companion object {
        /** 实际 Worker 共用的执行边界；结果反馈只观察，不可把已保存结果改写为调度失败。 */
        internal suspend fun executeUpload(upload: suspend () -> BackupTargetPublicationResult,
            onResult: (BackupTargetPublicationResult) -> Unit, onFailure: suspend (Throwable) -> Unit): Result {
            return try {
                /** 非终态补传必须由协调器的持久化结果明确批准。 */
                val outcome = upload()
                currentCoroutineContext().ensureActive()
                try { onResult(outcome) } catch (_: Exception) { /* 尽力诊断不能反转已经保存的业务结果。 */ }
                if (outcome is BackupTargetPublicationResult.RetryScheduled) Result.retry() else Result.success()
            } catch (failure: Throwable) {
                try { onFailure(failure) } catch (_: Exception) { /* 诊断失败不覆盖原始取消或业务失败。 */ }
                if (failure is CancellationException) throw failure
                Result.failure()
            }
        }
    }
}
