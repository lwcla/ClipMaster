package com.cla.clip.master.backup

import com.cla.clip.base.general.backup.settings.BackupResultTarget
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 分派测试只替换队列和结果提交；不假称 JVM 能证明真实 WorkManager 调度。 */
class BackupExecutionCoordinatorTest {
    /** 已知系统抢占和系统处理只更新等待阶段，不能覆盖最近结果或反转已终结目标。 */
    @Test fun preemptionAndSystemProcessingDoNotRecordRemoteFailure() = runBlocking {
        // stopReason 覆盖两类真实平台暂停；accepted 覆盖当前有效及事务拒绝的已终结目标。
        for (stopReason in listOf(androidx.work.WorkInfo.STOP_REASON_PREEMPT, androidx.work.WorkInfo.STOP_REASON_SYSTEM_PROCESSING)) {
            for (accepted in listOf(true, false)) {
                /** 本次条件排队调用次数，暂停分支必须尝试一次而不能直接结束。 */
                var queues = 0
                /** 最近失败结果的保存请求，暂停及事务拒绝时均应保持为空。 */
                val failures = mutableListOf<String>()
                assertEquals(accepted, BackupExecutionCoordinator.finishInterruption(BackupResultTarget.WebDav,
                    stopReason,
                    // 暂停仅交给条件事务决定是否排队，拒绝不再退回写失败的分支。
                    requeue = { queues++; accepted },
                    // reason 是待保存的低敏失败码；这两类系统暂停不应产生该副作用。
                    recordFailure = { reason -> failures += reason }))
                assertEquals("系统暂停只能尝试一次条件排队", 1, queues)
                assertTrue("系统暂停不能提交最近备份失败结果", failures.isEmpty())
            }
        }
    }

    /** 系统设备暂停只重新排队，不写最近失败结果；成功或过期时条件排队拒绝也不能写失败。 */
    @Test fun systemStopPreservesHistoryAndOnlyRequeuesCurrentRemote() = runBlocking {
        /** 是否接受排队由运行态事务决定，覆盖正常、过期及终态拒绝。 */
        for (accepted in listOf(true, false)) {
            /** 外部副作用是该窄规则的业务契约。 */
            var queues = 0
        /** 最近失败提交副作用，暂停分支应保持为空。 */
        val failures = mutableListOf<String>()
            assertEquals(accepted, BackupExecutionCoordinator.finishInterruption(BackupResultTarget.WebDav,
                androidx.work.WorkInfo.STOP_REASON_DEVICE_STATE, requeue = { queues++; accepted },
                recordFailure = { failures += it }))
            assertEquals(1, queues)
            assertTrue(failures.isEmpty())
        }
    }

    /** 应用取消、未知停止和本地中断不能通过云端恢复规则复活。 */
    @Test fun appCancellationAndLocalStopKeepTerminalContract() = runBlocking {
        /** 停止原因与目标共同决定真实失败原因，不猜测为系统暂停。 */
        val cases = listOf(Triple(BackupResultTarget.WebDav, androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP, "upload_interrupted_cancelled"),
            Triple(BackupResultTarget.WebDav, -256, "upload_interrupted"),
            Triple(BackupResultTarget.Local, androidx.work.WorkInfo.STOP_REASON_DEVICE_STATE, "local_interrupted"))
        // target/reason/expected 分别表示目标、平台原因与预期保存码。
        for ((target, reason, expected) in cases) {
            /** 保存的真实原因列表。 */
            val failures = mutableListOf<String>()
            BackupExecutionCoordinator.finishInterruption(target, reason, requeue = { error("不能重新排队") }, recordFailure = { failures += it })
            assertEquals(listOf(expected), failures)
        }
    }
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
