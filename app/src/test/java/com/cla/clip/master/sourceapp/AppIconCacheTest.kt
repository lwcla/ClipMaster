package com.cla.clip.master.sourceapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 用字符串代替 Bitmap，保护跨入口共享、失败冷却和不可变展示快照。 */
class AppIconCacheTest {
    /** 不可中断平台读取在取消后返回失败，也不得写入负缓存。 */
    @Test fun cancelledBlockingReadDoesNotCacheFailure() = runBlocking {
        /** 模拟系统调用已开始的信号。 */
        val started = CompletableDeferred<Unit>()
        /** 模拟同步调用最终返回的屏障。 */
        val released = CompletableDeferred<Unit>()
        /** 真实重读计数。 */
        var reads = 0
        /** 首次调用忽略取消返回空，后续调用可以成功。 */
        val cache = AppIconCache<String>(read = {
            if (++reads == 1) withContext(NonCancellable) {
                started.complete(Unit)
                released.await()
                AppIconEntry(null, null)
            } else AppIconEntry("ready", null)
        })
        /** 页面离开时被取消的读取任务。 */
        val request = async { cache.load("fixture") }
        started.await()
        request.cancel()
        released.complete(Unit)
        request.join()
        assertEquals(0, cache.size)
        assertEquals("ready", cache.load("fixture").icon)
        assertEquals(2, reads)
    }
    /** 系统图标变化不更新成功缓存；新进程对应的新缓存才读取最新结果。 */
    @Test fun repeatedPackageSharesSnapshot() = runBlocking {
        /** 合成系统读取次数。 */
        var reads = 0
        /** 模拟系统当前图标和主色，可在两次请求之间更新。 */
        var systemEntry = AppIconEntry("icon", 7)
        /** 单一图标/颜色快照缓存。 */
        val cache = AppIconCache<String>(read = { reads++; systemEntry })
        /** 首次装配后已经发布的不可变结果。 */
        val first = cache.load("fixture")
        systemEntry = AppIconEntry("updated", 8)
        assertSame(first, cache.load("fixture"))
        assertEquals(1, reads)
        assertEquals("icon", first.icon)
        assertEquals(7, first.primaryColor)
        /** 模拟进程重启，Manager 重新持有空缓存。 */
        val restartedCache = AppIconCache<String>(read = { reads++; systemEntry })
        assertEquals(systemEntry, restartedCache.load("fixture"))
        assertEquals(2, reads)
    }

    /** 同包名并发请求只执行一次读取，不依赖线程睡眠。 */
    @Test fun concurrentRequestsShareOneRead() = runBlocking {
        /** 控制系统调用完成时机的测试屏障。 */
        val gate = CompletableDeferred<Unit>()
        /** 已开始读取的测试信号。 */
        val started = CompletableDeferred<Unit>()
        /** 读取计数仅在测试调度器串行修改。 */
        var reads = 0
        /** 两个入口共用的缓存。 */
        val cache = AppIconCache<String>(read = { reads++; started.complete(Unit); gate.await(); AppIconEntry("icon", 9) })
        /** 首页请求先进入系统调用。 */
        val first = async { cache.load("fixture") }
        started.await()
        /** 菜单请求等待同包名正在进行的读取。 */
        val second = async { cache.load("fixture") }
        gate.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals(1, reads)
    }

    /** 不可用短期冷却，未知来源不查询，冷却结束后可以重试。 */
    @Test fun failureCoolsDownAndBlankSkipsRead() = runBlocking {
        /** 可注入时钟避免依赖真实等待。 */
        var time = 0L
        /** 合成失败读取次数。 */
        var reads = 0
        /** 失败有效期为十毫秒的测试缓存。 */
        val cache = AppIconCache<String>(read = { reads++; AppIconEntry(null, null) }, nowMillis = { time }, failureTtlMillis = 10)
        assertNull(cache.load("").icon)
        cache.load("fixture")
        cache.load("fixture")
        assertEquals(1, reads)
        time = 11
        cache.load("fixture")
        assertEquals(2, reads)
    }

    /** 取消不产生负缓存，下一个消费者仍能取得图标。 */
    @Test fun cancellationDoesNotPoisonCache() = runBlocking {
        /** 首次读取抛取消，第二次正常。 */
        var reads = 0
        /** 没有 Android 依赖的取消边界。 */
        val cache = AppIconCache<String>(read = { if (++reads == 1) throw CancellationException(); AppIconEntry("icon", null) })
        try { cache.load("fixture"); fail("应传播取消") } catch (_: CancellationException) { }
        assertEquals("icon", cache.load("fixture").icon)
        assertEquals(2, reads)
    }

    /** LRU 淘汰不修改已有快照；内存清理期间返回的旧结果不回填缓存。 */
    @Test fun evictionAndMemoryClearKeepPublishedSnapshots() = runBlocking {
        /** 读取代次构造不同的图标。 */
        var reads = 0
        /** 容量仅一项，便于验证淘汰。 */
        val cache = AppIconCache<String>(read = { AppIconEntry("$it-${++reads}", reads) }, maxEntries = 1)
        /** UI 保留的第一代快照。 */
        val first = cache.load("a")
        cache.load("b")
        assertNotSame(first, cache.load("a"))
        assertEquals("a-1", first.icon)
        /** 测试读取过程中发生内存清理。 */
        lateinit var cleared: AppIconCache<String>
        cleared = AppIconCache(read = { cleared.clear(); AppIconEntry("old", null) })
        cleared.load("a")
        assertEquals(0, cleared.size)
    }
}
