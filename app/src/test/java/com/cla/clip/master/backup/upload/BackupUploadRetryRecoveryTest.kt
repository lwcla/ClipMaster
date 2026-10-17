package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.settings.BackupWebDavRetryDecision
import com.cla.clip.master.backup.BackupTargetPublicationResult
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 暂时失败必须等待事务确认，过期及存储失败都不能产生补传许可。 */
class BackupUploadRetryRecoveryTest {
    /** 尚未落盘不能将等待结果交给 Worker，批准后携带真实业务计数。 */
    @Test fun transientFailureWaitsForDurableQueueBeforeRetry() = runBlocking {
        /** 显式控制事务完成，避免真实计时和线程偶然顺序。 */
        val saved = CompletableDeferred<BackupWebDavRetryDecision>()
        /** 被测恢复路径在保存点挂起。 */
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            BackupUploadRetryRecovery.recover(timeout()) { saved.await() }
        }
        assertFalse(result.isCompleted)
        saved.complete(BackupWebDavRetryDecision.Queued(1))
        assertEquals(BackupTargetPublicationResult.RetryScheduled(1), result.await())
    }

    /** 耗尽回到原失败提交路径，过期则静默退出而不提交新失败。 */
    @Test fun exhaustedAndObsoleteHaveDifferentCompletionContracts() = runBlocking {
        assertNull(BackupUploadRetryRecovery.recover(timeout()) { BackupWebDavRetryDecision.Exhausted })
        assertEquals(BackupTargetPublicationResult.Skipped,
            BackupUploadRetryRecovery.recover(timeout()) { BackupWebDavRetryDecision.Obsolete })
        assertNull(BackupUploadRetryRecovery.recover(IOException()) { error("本地 IO 不得申请") })
    }

    /** 保存失败转换为独立运行态错误，不允许被当作原网络失败再次申请额度。 */
    @Test fun retryStoreFailureDoesNotReturnRetry() {
        assertThrows(BackupRetryPersistenceFailure::class.java) {
            runBlocking { BackupUploadRetryRecovery.recover(timeout()) { throw IOException("synthetic") } }
        }
    }

    /** 调用期间取消保持取消语义，不伪装成存储或网络失败。 */
    @Test fun cancellationDuringRetryClaimPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking { BackupUploadRetryRecovery.recover(timeout()) { throw CancellationException("synthetic") } }
        }
    }

    /** 已取消协程里的迟到超时不会消费次数。 */
    @Test fun cancelledCoroutineCannotClaimRetryForLateTimeout() = runBlocking {
        /** 本轮取消应阻止后续事务。 */
        var claims = 0
        /** 独立子任务可以取消而不影响测试父协程。 */
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            BackupUploadRetryRecovery.recover(timeout()) { claims++; BackupWebDavRetryDecision.Queued(1) }
        }
        job.join()
        assertEquals(0, claims)
    }

    /** 使用合成请求超时，不读取真实地址或文件。 */
    private fun timeout() = BackupFailure.RemoteFailed(SocketTimeoutException())
}
