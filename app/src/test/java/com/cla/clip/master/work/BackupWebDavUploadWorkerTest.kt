package com.cla.clip.master.work

import androidx.work.ListenableWorker.Result
import com.cla.clip.master.backup.BackupTargetPublicationResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Worker 的实际执行边界回归，无须启动 Android 调度器或假装证明系统时机。 */
class BackupWebDavUploadWorkerTest {
    /** 等待返回 retry，完成/过期返回 success，都不触发异常完成反馈。 */
    @Test fun queuedRetryDoesNotFinishAsFailure() = runBlocking {
        assertEquals(Result.retry(), BackupWebDavUploadWorker.executeUpload(
            upload = { BackupTargetPublicationResult.RetryScheduled(1) }, onResult = {}, onFailure = { error("不能报失败") }))
        // outcome 覆盖真实完成与旧轮次跳过，后者不等同远端成功。
        for (outcome in listOf(BackupTargetPublicationResult.Completed, BackupTargetPublicationResult.Skipped)) {
            assertEquals(Result.success(), BackupWebDavUploadWorker.executeUpload(
                upload = { outcome }, onResult = {}, onFailure = { error("不能报失败") }))
        }
    }

    /** 永久失败返回 failure，取消传播给 WorkManager，二者均不自动返回 retry。 */
    @Test fun terminalFailureAndCancellationStayDistinct() = runBlocking<Unit> {
        /** 真实结束证据回调只调用一次。 */
        var failures = 0
        assertEquals(Result.failure(), BackupWebDavUploadWorker.executeUpload(
            upload = { throw IOException() }, onResult = { error("不能完成") }, onFailure = { failures++ }))
        assertEquals(1, failures)
        assertThrows(CancellationException::class.java) {
            runBlocking { BackupWebDavUploadWorker.executeUpload(
                upload = { throw CancellationException() }, onResult = { error("不能完成") }, onFailure = {}) }
        }
    }
}
