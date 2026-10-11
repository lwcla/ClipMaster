package com.cla.clip.master.sourceapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 图像只由当前 UI 绑定持有；取消和离场不得留下无主结果。 */
class AppIconUiSessionTest {
    /** 构造与重组不取图，重复加载复用完整图标/颜色。 */
    @Test fun waitsForVisibleLoadAndKeepsOneResult() = runBlocking {
        /** 合成平台读取次数。 */
        var reads = 0
        /** 同源图像与颜色不拆成两个状态。 */
        val result = AppIconEntry("image", 7)
        /** 本测试不需要 Android Bitmap。 */
        val session = AppIconUiSession("app") { reads++; result }
        assertNull(session.state.value)
        assertEquals(0, reads)
        session.load()
        session.load()
        assertSame(result, session.state.value)
        assertEquals(1, reads)
    }

    /** 多行同包名复用 Manager 缓存，局部状态不复制图像。 */
    @Test fun bindingsShareCachedIconButReleaseIndependently() = runBlocking {
        /** 唯一平台读取计数。 */
        var reads = 0
        /** 复用生产缓存验证跨绑定共享而非假造独立结果。 */
        val cache = AppIconCache(read = { _: String -> reads++; AppIconEntry("image", 7) })
        /** 第一处 UI 绑定。 */
        val first = AppIconUiSession("app", cache::load)
        /** 第二处 UI 绑定。 */
        val second = AppIconUiSession("app", cache::load)
        coroutineScope { awaitAll(async { first.load() }, async { second.load() }) }
        assertSame(first.state.value, second.state.value)
        first.close()
        assertNull(first.state.value)
        assertEquals("image", second.state.value?.icon)
        assertEquals(1, reads)
    }

    /** 空身份直接默认，不访问系统或阻塞正文。 */
    @Test fun blankPackageUsesDefaultWithoutReading() = runBlocking {
        listOf(null, "", " ").forEach { packageName ->
            /** 空身份加载器若执行则立即失败，保护零调用契约。 */
            val session = AppIconUiSession<String>(packageName) { error("不应读取") }
            session.load()
            assertEquals(AppIconEntry<String>(null, null), session.state.value)
        }
    }

    /** 生命周期取消不变成失败结果；重新可见仍可重试。 */
    @Test fun cancellationLeavesUnresolvedStateForResume() = runBlocking {
        /** 已进入异步读取的通知。 */
        val started = CompletableDeferred<Unit>()
        /** 第一次读取取消，第二次读取成功。 */
        var reads = 0
        /** 生命周期块使用同一个局部会话重试。 */
        val session = AppIconUiSession("app") {
            if (++reads == 1) { started.complete(Unit); awaitCancellation() }
            AppIconEntry("image", 9)
        }
        /** 首次可见读取。 */
        val request = launch { session.load() }
        started.await()
        request.cancelAndJoin()
        assertNull(session.state.value)
        session.load()
        assertEquals(AppIconEntry("image", 9), session.state.value)
        assertEquals(2, reads)
    }

    /** 关闭后释放状态，且不能被不可中断的晚到结果重新占用。 */
    @Test fun closeRejectsLateResultAndFurtherLoads() = runBlocking {
        /** 平台同步调用已开始的通知。 */
        val started = CompletableDeferred<Unit>()
        /** 模拟不可中断系统 API 的返回许可。 */
        val returned = CompletableDeferred<Unit>()
        /** 检查关闭后不会重启系统调用。 */
        var reads = 0
        /** 关闭可与正在执行的调用交错。 */
        val session = AppIconUiSession("app") {
            reads++
            withContext(NonCancellable) { started.complete(Unit); returned.await() }
            AppIconEntry("image", 7)
        }
        /** 不取消请求也不能越过已关闭边界。 */
        val request = launch { session.load() }
        started.await()
        session.close()
        returned.complete(Unit)
        request.join()
        session.load()
        assertNull(session.state.value)
        assertEquals(1, reads)
    }

    /** 普通失败默认交付，不能把取消误当作这个已完成状态。 */
    @Test fun ordinaryFailureCompletesWithDefault() = runBlocking {
        /** 故障用合成异常，不记录应用名单或真实系统数据。 */
        val session = AppIconUiSession<String>("app") { error("fixture") }
        session.load()
        assertEquals(AppIconEntry<String>(null, null), session.state.value)
    }
}
