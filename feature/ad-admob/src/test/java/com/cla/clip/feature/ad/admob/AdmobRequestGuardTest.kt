package com.cla.clip.feature.ad.admob
import org.junit.Assert.*
import org.junit.Test

/** 保护超时、撤回与迟到回调之间的一次性资源所有权。 */
class AdmobRequestGuardTest {
    /** 超时终止后任何迟到加载均不能获得广告所有权。 */
    @Test fun timeoutRejectsLateLoad() {
        /** 单次请求状态，不依赖 Android 或真实线程。 */
        val guard = AdmobRequestGuard()
        assertTrue(guard.finishLoading())
        assertFalse(guard.finishLoading())
        assertFalse(guard.canAcceptAd)
    }
    /** 页面关闭可以重复执行且广告事件只能发送一次。 */
    @Test fun closeAndEventsAreIdempotent() {
        /** 单次请求状态。 */
        val guard = AdmobRequestGuard()
        assertTrue(guard.record("loaded"))
        assertFalse(guard.record("loaded"))
        assertTrue(guard.close())
        assertFalse(guard.close())
        assertFalse(guard.record("impression"))
    }
    /** 加载完成仅能领取一次，并不自动关闭展示生命周期。 */
    @Test fun loadedRequestRetainsDisplayLifetime() {
        /** 加载前可接收一次素材。 */
        val guard = AdmobRequestGuard()
        assertTrue(guard.canAcceptAd)
        assertTrue(guard.finishLoading())
        assertTrue(guard.record("impression"))
        assertTrue(guard.close())
    }
}
