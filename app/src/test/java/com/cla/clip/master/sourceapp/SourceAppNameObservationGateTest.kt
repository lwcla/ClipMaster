package com.cla.clip.master.sourceapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** 来源名称搜索增加连续观察后，后台不能继续收集数据库投影。 */
class SourceAppNameObservationGateTest {
    /** 隐藏零观察，显示开始，隐藏取消；不依赖真实时间或 Android 生命周期。 */
    @Test fun observationOnlyRunsWhileVisible() = runBlocking {
        /** 独立页面门控，不影响其他页面。 */
        val gate = SourceAppLoadGate()
        /** 页面绑定身份。 */
        val owner = Any()
        /** 数据库观察实际开始次数。 */
        var reads = 0
        /** 开始和取消信号供测试确定协程执行边界。 */
        val started = CompletableDeferred<Unit>()
        /** 页面隐藏后的订阅取消信号。 */
        val stopped = CompletableDeferred<Unit>()
        /** 持续观察模拟 Room flow，父任务在 finally 总会清理。 */
        val observing = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.observe { flow {
                reads++
                started.complete(Unit)
                try { emit("ready"); awaitCancellation() } finally { stopped.complete(Unit) }
            } }.collect { }
        }
        try {
            yield()
            assertEquals(0, reads)
            gate.setActive(owner, true)
            withTimeout(2_000) { started.await() }
            assertEquals(1, reads)
            gate.setActive(owner, false)
            withTimeout(2_000) { stopped.await() }
        } finally {
            observing.cancel()
            observing.join()
        }
    }
}
