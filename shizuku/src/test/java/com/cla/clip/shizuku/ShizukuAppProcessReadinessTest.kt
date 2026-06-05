package com.cla.clip.shizuku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** app 主进程探活与唤醒策略测试，保护 Provider 提交前的前置唤醒规则。 */
class ShizukuAppProcessReadinessTest {

    @Test
    /** callback 已经可达时不应启动 NoDisplay Activity，避免常态路径重复拉起主进程。 */
    fun ensureReadyReturnsReadyWhenCallbackPings() = runBlocking {
        /** 当前 callback 状态，初始即为可 ping 通的 app 主进程 callback。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(TestCallback(id = "alive"))
        /** app 唤醒次数；callback 可达时必须保持为 0。 */
        var wakeCount = 0
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            wakeAppProcess = {
                wakeCount += 1
                successfulWake()
            }
        )

        /** 探活结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.Ready)
        assertEquals(0, wakeCount)
        assertEquals("ping_ok", result.appPingResult)
    }

    @Test
    /** callback 为空时应拉起 NoDisplay Activity 并等待新 callback 回来。 */
    fun ensureReadyWakesWhenCallbackMissing() = runBlocking {
        /** 当前 callback 状态，初始为空表示 app 主进程不可达。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(null)
        /** app 唤醒次数，用于确认只拉起一次。 */
        var wakeCount = 0
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            wakeAppProcess = {
                wakeCount += 1
                callbackFlow.value = TestCallback(id = "rebound")
                successfulWake()
            }
        )

        /** 探活和唤醒结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.WakeSucceeded)
        assertEquals(1, wakeCount)
        assertEquals(AppWakeMode.ACTIVITY_NO_DISPLAY, result.appWakeMode)
        assertEquals(true, result.appWakeResult)
        assertEquals(true, result.callbackRebound)
    }

    @Test
    /** NoDisplay Activity 唤醒输出也应被视为 app 主进程唤醒命令已被系统接受。 */
    fun ensureReadyAcceptsWakeActivityOutput() = runBlocking {
        /** 当前 callback 状态，初始为空表示 app 主进程不可达。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(null)
        /** NoDisplay Activity 唤醒次数，用于确认 fallback 命令只执行一次。 */
        var wakeCount = 0
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            wakeAppProcess = {
                wakeCount += 1
                callbackFlow.value = TestCallback(id = "activity")
                activityWake()
            }
        )

        /** 探活和唤醒结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.WakeSucceeded)
        assertEquals(1, wakeCount)
        assertEquals(AppWakeMode.ACTIVITY_NO_DISPLAY, result.appWakeMode)
        assertEquals("activity", callbackFlow.value?.id)
    }

    @Test
    /** NoDisplay Activity 命令成功但 callback 未回流时，应输出专用 reasonCode 方便区分生命周期竞态。 */
    fun ensureReadyMarksWakeActivityCallbackTimeout() = runBlocking {
        /** 当前 callback 状态，保持为空以模拟 Activity 已启动但主进程没有重新 setCallback。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(null)
        /** 待测探活协作者，使用很短 callback 等待时间避免测试依赖真实等待。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            callbackWaitMillis = 1L,
            wakeAppProcess = { activityWake() }
        )

        /** 探活和唤醒结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.WakeFailed)
        assertEquals(AppWakeMode.ACTIVITY_NO_DISPLAY, result.appWakeMode)
        assertEquals(true, result.appWakeResult)
        assertEquals(false, result.callbackRebound)
        assertEquals("wake_activity_started_callback_timeout", result.reasonCode)
    }

    @Test
    /** ping 超时时必须按 callback 不可信处理并进入唤醒流程。 */
    fun ensureReadyWakesWhenPingTimesOut() = runBlocking {
        /** 当前 callback 状态，初始 callback 会故意延迟超过超时时间。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(TestCallback(id = "slow", pingDelayMillis = 50L))
        /** app 唤醒次数，用于确认超时后执行唤醒。 */
        var wakeCount = 0
        /** 待测探活协作者，使用 1ms ping 超时让测试快速完成。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            pingTimeoutMillis = 1L,
            wakeAppProcess = {
                wakeCount += 1
                callbackFlow.value = TestCallback(id = "rebound")
                successfulWake()
            }
        )

        /** 探活和唤醒结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.WakeSucceeded)
        assertEquals(1, wakeCount)
        assertEquals("ping_ok", result.appPingResult)
    }

    @Test
    /** ping 抛出 Binder 类异常时应清空旧 callback 并等待新 callback。 */
    fun ensureReadyClearsCallbackWhenPingThrows() = runBlocking {
        /** 旧 callback；探活时会模拟 Binder transact 失败。 */
        val brokenCallback = TestCallback(id = "broken", pingThrows = true)
        /** 当前 callback 状态，初始指向不可用旧 callback。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(brokenCallback)
        /** app 唤醒次数，用于确认异常后执行唤醒。 */
        var wakeCount = 0
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            wakeAppProcess = {
                wakeCount += 1
                callbackFlow.value = TestCallback(id = "new")
                successfulWake()
            }
        )

        /** 探活和唤醒结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.WakeSucceeded)
        assertEquals(1, wakeCount)
        assertEquals("new", callbackFlow.value?.id)
    }

    @Test
    /** 唤醒命令超时时应返回 WakeFailed，调用方再走 Provider 兼容查询。 */
    fun ensureReadyReturnsWakeFailedWhenCommandTimesOut() = runBlocking {
        /** 当前 callback 状态，初始为空表示需要唤醒。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(null)
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            wakeAppProcess = {
                AppWakeCommandResult(
                    wakeMode = AppWakeMode.ACTIVITY_NO_DISPLAY,
                    exitCode = -1,
                    output = "timeout",
                    timedOut = true
                )
            }
        )

        /** 探活和唤醒结果。 */
        val result = readiness.ensureReady()

        assertTrue(result is ShizukuAppProcessReadinessResult.WakeFailed)
        assertEquals("wake_command_timeout", result.reasonCode)
        assertEquals(false, result.readyForProviderQuery)
    }

    @Test
    /** 唤醒失败后短时间内再次进入应被 cooldown 拦截，避免连续拉起 NoDisplay Activity。 */
    fun ensureReadySkipsWakeDuringCooldown() = runBlocking {
        /** 当前 callback 状态，始终为空表示 app 主进程没有回来。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(null)
        /** 假时钟时间，保持不变即可命中 cooldown。 */
        var nowMillis = 1_000L
        /** app 唤醒次数；第二次应因 cooldown 不再增加。 */
        var wakeCount = 0
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            clockMillis = { nowMillis },
            wakeAppProcess = {
                wakeCount += 1
                AppWakeCommandResult(
                    wakeMode = AppWakeMode.ACTIVITY_NO_DISPLAY,
                    exitCode = 1,
                    output = "Error: background restricted",
                    timedOut = false
                )
            }
        )

        /** 首次唤醒失败结果。 */
        val firstResult = readiness.ensureReady()
        /** cooldown 内的第二次探活结果。 */
        val secondResult = readiness.ensureReady()

        assertTrue(firstResult is ShizukuAppProcessReadinessResult.WakeFailed)
        assertTrue(secondResult is ShizukuAppProcessReadinessResult.CooldownSkipped)
        assertEquals(1, wakeCount)
        nowMillis += 1
    }

    @Test
    /** 并发事件必须共享同一轮唤醒，但每个事件可在调用方保留自己的剪贴板快照。 */
    fun ensureReadySharesWakeAcrossConcurrentEvents() = runBlocking {
        /** 当前 callback 状态，初始为空表示第一批并发事件都需要唤醒。 */
        val callbackFlow = MutableStateFlow<TestCallback?>(null)
        /** app 唤醒次数；并发 ensureReady 只能共享一次命令。 */
        var wakeCount = 0
        /** 待测探活协作者。 */
        val readiness = readiness(
            callbackFlow = callbackFlow,
            wakeAppProcess = {
                wakeCount += 1
                callbackFlow.value = TestCallback(id = "shared")
                successfulWake()
            }
        )

        /** 第一个并发探活任务。 */
        val firstTask = async { readiness.ensureReady() }
        /** 第二个并发探活任务。 */
        val secondTask = async { readiness.ensureReady() }
        /** 第一个并发探活结果。 */
        val firstResult = firstTask.await()
        /** 第二个并发探活结果。 */
        val secondResult = secondTask.await()

        assertEquals(1, wakeCount)
        assertTrue(firstResult.readyForProviderQuery)
        assertTrue(secondResult.readyForProviderQuery)
    }

    /**
     * 构造待测探活协作者。
     *
     * @param callbackFlow 当前 callback 状态。
     * @param clockMillis 假时钟来源。
     * @param pingTimeoutMillis callback 探活超时时间。
     * @param callbackWaitMillis 唤醒后等待 callback 回流的测试窗口。
     * @param wakeAppProcess app 唤醒命令替身。
     */
    private fun readiness(
        callbackFlow: MutableStateFlow<TestCallback?>,
        clockMillis: () -> Long = { System.currentTimeMillis() },
        pingTimeoutMillis: Long = 300L,
        callbackWaitMillis: Long = 2_500L,
        wakeAppProcess: () -> AppWakeCommandResult,
    ): ShizukuAppProcessReadiness<TestCallback> {
        return ShizukuAppProcessReadiness(
            callbackFlow = callbackFlow,
            pingCallback = { callback ->
                callback.ping()
            },
            wakeAppProcess = wakeAppProcess,
            clockMillis = clockMillis,
            pingDispatcher = Dispatchers.Unconfined,
            pingTimeoutMillis = pingTimeoutMillis,
            callbackWaitMillis = callbackWaitMillis
        )
    }

    /** 构造成功的 NoDisplay Activity 唤醒命令结果。 */
    private fun successfulWake(): AppWakeCommandResult {
        return AppWakeCommandResult(
            wakeMode = AppWakeMode.ACTIVITY_NO_DISPLAY,
            exitCode = 0,
            output = "Starting: Intent { cmp=com.cla.clip.master/.wake.ShizukuWakeActivity }",
            timedOut = false
        )
    }

    /** 构造成功的 NoDisplay Activity 唤醒命令结果。 */
    private fun activityWake(): AppWakeCommandResult {
        return AppWakeCommandResult(
            wakeMode = AppWakeMode.ACTIVITY_NO_DISPLAY,
            exitCode = 0,
            output = "Starting: Intent { cmp=com.cla.clip.master/.wake.ShizukuWakeActivity }",
            timedOut = false
        )
    }

    /**
     * 测试用 callback。
     *
     * @param id callback 身份，用于确认旧 callback 被新 callback 替换。
     * @param pingDelayMillis 探活延迟，非 0 时用于模拟 Binder 卡顿。
     * @param pingThrows 是否在探活时抛出异常。
     */
    private data class TestCallback(
        val id: String,
        val pingDelayMillis: Long = 0L,
        val pingThrows: Boolean = false,
    ) {
        /** 执行无副作用探活，按测试参数返回成功、延迟或异常。 */
        suspend fun ping(): Boolean {
            if (pingDelayMillis > 0L) {
                delay(pingDelayMillis)
            }
            if (pingThrows) {
                error("Binder transact failed")
            }
            return true
        }
    }
}
