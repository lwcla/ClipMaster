package com.cla.clip.master.preview

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 许可执行器的测试观察是否真的执行和取消网络工作，不依赖 Android 或真实站点。 */
class PreviewPermissionTest {
    /** 缺少允许时不调用网络工作。 */
    @Test fun disabledPreviewNeverStartsWork() = runBlocking {
        /** 被调用计数用于验证入口封锁，不返回虚构网络响应。 */
        var calls = 0
        assertNull(withPreviewPermission(flowOf(false)) { calls++; "metadata" })
        assertEquals(0, calls)
    }

    /** 开关打开时允许执行一次并返回元信息。 */
    @Test fun enabledPreviewReturnsWorkResult() = runBlocking {
        assertEquals("metadata", withPreviewPermission(flowOf(true)) { "metadata" })
    }

    /** 关闭在途请求应取消网络工作，但调用方继续保有此前保存的文本。 */
    @Test fun disablingCancelsInFlightWorkWithoutCancellingCaller() = runBlocking {
        /** 模拟已提交开关变化。 */
        val enabled = MutableStateFlow(true)
        /** 握手信号使测试不依赖真实延时。 */
        val started = CompletableDeferred<Unit>()
        /** 工作清理信号，验证取消确实到达子任务。 */
        val stopped = CompletableDeferred<Unit>()
        /** 外层保存协程模拟真实调用方。 */
        val task = async {
            withPreviewPermission(enabled) {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        started.await()
        enabled.value = false
        assertNull(task.await())
        assertTrue(stopped.isCompleted)
        assertTrue(currentCoroutineContext().isActive)
    }

    /** 设置无法读取时安全跳过预览，不能以旧值发请求。 */
    @Test fun settingsFailureSkipsNetwork() = runBlocking {
        /** 模拟设置流故障。 */
        val failure: Flow<Boolean> = flow { throw IOException("synthetic") }
        assertNull(withPreviewPermission(failure) { fail("不应执行网络"); "metadata" })
    }

    /** 页面/调用方取消必须继续传播，不能被隐私降级吞掉。 */
    @Test fun callerCancellationStopsPreview() = runBlocking {
        /** 确保工作已经开始。 */
        val started = CompletableDeferred<Unit>()
        /** 模拟一个等待中的网络子任务。 */
        val task = async {
            withPreviewPermission(flowOf(true)) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
    }
}
