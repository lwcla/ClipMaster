package com.cla.clip.master.provider

import com.cla.clip.shizuku.ClipboardBridgeContract
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Test

/** 身份查询 Binder 桥测试，确保主线程拒绝、超时和持久化失败均给出可重试协议结果。 */
class ClipboardBridgeShizukuIdentityQueryBridgeTest {
    @Test
    /** 主线程请求必须立即拒绝，不能进入 DataStore 查询。 */
    fun mainThreadQueryDoesNotEnterStore() {
        /** DataStore 查询调用次数；主线程保护后必须为零。 */
        var queryCalls = 0
        /** 桥接结果；测试通过显式线程标志避免依赖 Android Looper。 */
        val result = ClipboardBridgeShizukuIdentityQueryBridge.query(isMainThread = true) {
            queryCalls += 1
            ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_OK)
        }
        assertEquals(ClipboardBridgeContract.CODE_TIMEOUT, result.resultCode)
        assertEquals(0, queryCalls)
    }

    @Test
    /** 超过等待上限时返回明确超时结果，旧进程可以稍后重试。 */
    fun slowStoreQueryTimesOut() {
        /** 桥接结果；故意让假存储挂起超过测试上限。 */
        val result = ClipboardBridgeShizukuIdentityQueryBridge.query(isMainThread = false, timeoutMs = 10L) {
            delay(100L)
            ClipboardBridgeResult.of(ClipboardBridgeContract.CODE_OK)
        }
        assertEquals(ClipboardBridgeContract.CODE_TIMEOUT, result.resultCode)
    }

    @Test
    /** 持久化异常不会泄露半成品进程名，并映射成显式失败原因。 */
    fun storeFailureReturnsRetryableMissingIdentity() {
        /** 桥接结果；异常文案不应进入协议结果。 */
        val result = ClipboardBridgeShizukuIdentityQueryBridge.query(isMainThread = false) {
            error("private path")
        }
        assertEquals(ClipboardBridgeContract.CODE_SHIZUKU_PROCESS_MISSING, result.resultCode)
        assertEquals("identity_store_failed", result.reasonCode)
        assertEquals(null, result.shizukuProcessName)
    }
}
