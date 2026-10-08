package com.cla.clip.master.backup

import com.cla.clip.base.general.backup.settings.BackupResultTarget
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 分派测试只替换队列和结果提交；不假称 JVM 能证明真实 WorkManager 调度。 */
class BackupExecutionCoordinatorTest {
    /** 第二目标取消时，已经成功入队的第一目标继续执行。 */
    @Test fun secondTargetCancellationKeepsFirstDispatchedTargetAlive() = runBlocking {
        /** 需要终结的目标列表，不能包含已成功分派的本地。 */
        val ended = mutableListOf<BackupResultTarget>()
        try {
            BackupExecutionCoordinator.dispatchTargets(BackupResultTarget.entries,
                enqueue = { target -> if (target == BackupResultTarget.WebDav) throw CancellationException("synthetic") },
                recordFailure = { target, _ -> ended += target })
            fail("原取消必须继续传播")
        } catch (_: CancellationException) {
            assertEquals(listOf(BackupResultTarget.WebDav), ended)
        }
    }
    /** 首目标入队取消时，未分派目标也要结束；不能留下没有 Worker 的等待状态。 */
    @Test fun cancellationFinishesUndispatchedTargetsAndStillPropagates() = runBlocking {
        /** 实际尝试入队的目标。 */
        val attempted = mutableListOf<BackupResultTarget>()
        /** 需要通过最新轮次条件事务终结的目标。 */
        val ended = mutableListOf<BackupResultTarget>()
        try {
            BackupExecutionCoordinator.dispatchTargets(BackupResultTarget.entries,
                enqueue = { target -> attempted += target; throw CancellationException("synthetic") },
                recordFailure = { target, _ -> ended += target })
            fail("原取消必须继续传播")
        } catch (_: CancellationException) {
            assertEquals(listOf(BackupResultTarget.Local), attempted)
            assertEquals(BackupResultTarget.entries, ended)
        }
    }
    /** 本地入队及错误落盘都失败时，仍必须尝试云端入队。 */
    @Test fun localEnqueueAndResultFailureDoNotPreventRemoteEnqueue() = runBlocking {
        /** 被尝试的目标顺序，不代表执行顺序。 */
        val attempted = mutableListOf<BackupResultTarget>()
        try {
            BackupExecutionCoordinator.dispatchTargets(BackupResultTarget.entries,
                enqueue = { target ->
                    attempted += target
                    if (target == BackupResultTarget.Local) throw IllegalStateException("synthetic_enqueue")
                }, recordFailure = { _, _ -> throw IllegalStateException("synthetic_store") })
            fail("结果存储失败应传播")
        } catch (_: IllegalStateException) {
            assertEquals(BackupResultTarget.entries, attempted)
        }
    }

    /** 本地消费故意挂起，提交云端仍能完成；队列提交不等待消费任务。 */
    @Test fun localConsumerSuspensionDoesNotDelayRemoteDispatch() = runBlocking {
        /** 可控本地消费阻塞，避免真实 IO 和时间等待。 */
        val localRelease = CompletableDeferred<Unit>()
        /** 用显式子任务模拟消费阶段，与短入队操作分离。 */
        var localJob: Job? = null
        /** 远端开始信号只由其入队动作产生。 */
        var remoteStarted = false
        try {
            BackupExecutionCoordinator.dispatchTargets(BackupResultTarget.entries,
                enqueue = { target ->
                    if (target == BackupResultTarget.Local) localJob = launch(start = CoroutineStart.UNDISPATCHED) { localRelease.await() }
                    else remoteStarted = true
                }, recordFailure = { _, failure -> throw failure })
            assertTrue(remoteStarted)
            assertTrue(localJob?.isActive == true)
        } finally { localJob?.cancelAndJoin() }
    }
}
