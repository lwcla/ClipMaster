package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.settings.BackupWebDavRetryDecision
import com.cla.clip.base.general.backup.webdav.WebDavUploadRetryClassifier
import com.cla.clip.master.backup.BackupTargetPublicationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 区分重试状态写盘失败和网络失败，不允许未落盘的等待继续补传。 */
internal class BackupRetryPersistenceFailure(cause: Throwable) : Exception("runtime_store_failed", cause)

/** 请求分类与重试事务的窄编排；入口协调器不持有次数规则或存储实现。 */
internal object BackupUploadRetryRecovery {
    /** 返回非终态恢复结果；null 表示沿用原失败提交，存储故障和取消明确向外传播。 */
    suspend fun recover(failure: Throwable, requestRetry: suspend () -> BackupWebDavRetryDecision): BackupTargetPublicationResult? {
        currentCoroutineContext().ensureActive()
        if (!WebDavUploadRetryClassifier.isRecoverable(failure)) return null
        /** 等待真实事务完成，不能提前将内存批准交给 Worker。 */
        val decision = try { requestRetry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (storeFailure: Exception) { throw BackupRetryPersistenceFailure(storeFailure) }
        currentCoroutineContext().ensureActive()
        return when (decision) {
            is BackupWebDavRetryDecision.Queued -> BackupTargetPublicationResult.RetryScheduled(decision.retryCount)
            BackupWebDavRetryDecision.Obsolete -> BackupTargetPublicationResult.Skipped
            BackupWebDavRetryDecision.Exhausted -> null
        }
    }
}
