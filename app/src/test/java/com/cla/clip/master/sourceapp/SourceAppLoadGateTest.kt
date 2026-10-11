package com.cla.clip.master.sourceapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** 页面不可见必须取消未完成读取，保留等待方，在恢复后重试。 */
class SourceAppLoadGateTest {
    /** 未激活零读取；停止取消当前项，恢复只重试这一项。 */
    @Test fun hiddenCancelsAndResumeRetries() = runBlocking {
        /** 页面可见引用身份，不依赖 Android Lifecycle。 */
        val owner = Any()
        /** 被测页面级装配门控。 */
        val gate = SourceAppLoadGate()
        /** 首次进入可取消读取的信号。 */
        val started = CompletableDeferred<Unit>()
        /** 当前读取已被取消的信号。 */
        val stopped = CompletableDeferred<Unit>()
        /** 实际读取次数。 */
        var reads = 0
        /** 父装配任务在后台等待，不能失败或交付未完成对象。 */
        val request = async {
            gate.run {
                if (++reads == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                "ready"
            }
        }
        yield()
        assertEquals(0, reads)
        gate.setActive(owner, true)
        started.await()
        gate.setActive(owner, false)
        stopped.await()
        yield()
        assertFalse(request.isCompleted)
        assertEquals(1, reads)
        gate.setActive(owner, true)
        assertEquals("ready", request.await())
        assertEquals(2, reads)
    }

    /** 多入口引用聚合，不让一个退出入口关闭另一个可见入口。 */
    @Test fun activeOwnersAndParentCancellation() = runBlocking {
        /** 两个独立生命周期绑定。 */
        val first = Any()
        /** 第二处可见绑定不受第一处退出影响。 */
        val second = Any()
        /** 被测聚合门控。 */
        val gate = SourceAppLoadGate()
        gate.setActive(first, true)
        gate.setActive(second, true)
        gate.setActive(first, false)
        assertEquals("ready", gate.run { "ready" })
        gate.setActive(second, false)
        /** 不可见期间取消父任务，不应保留独立后台等待。 */
        val waiting = async { gate.run { fail("不可启动"); Unit } }
        yield()
        waiting.cancel()
        waiting.join()
        assertTrue(waiting.isCancelled)
    }
}
