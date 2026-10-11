package com.cla.clip.base.general.backup

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 确定性阻塞合成请求验证 Call.cancel 接线与晚到异常，不连接真实网络。 */
class WebDavCancellationTest {
    /** 协程停止立即取消底层 Call，晚到 IOException 不能被保存为 RemoteFailed。 */
    @Test fun coroutineStopCancelsCallBeforeLateNetworkFailure() = runBlocking {
        /** 手动门闩确定请求开始及晚到错误时机，不用固定 sleep 猜测调度。 */
        val entered = CountDownLatch(1)
        /** 取消结束后才放行迟到网络异常。 */
        val release = CountDownLatch(1)
        /** 真实 OkHttp Call，用于验证实际取消副作用。 */
        val call = AtomicReference<Call>()
        /** 消费协程观察到的最终异常，应保持取消而非网络失败。 */
        val ended = CompletableDeferred<Throwable>()
        /** 合成阻塞由测试控制，IO 抛出仍走真实客户端失败映射。 */
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("synthetic_late_failure")
        }.build()
        /** 页面连接探测调用同一执行器，避免包准备掩盖 HTTP 取消规则。 */
        val job = launch {
            try { WebDavClient(http).uploadText(WebDavConfig("https://example.invalid", "", "", "/"), "probe.json", "{}") }
            catch (failure: Throwable) { ended.complete(failure) }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
            withTimeout(2000) { job.join() }
            assertTrue(call.get().isCanceled())
            assertTrue(ended.await() is CancellationException)
        } finally {
            call.get()?.cancel()
            release.countDown()
            job.cancelAndJoin()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
