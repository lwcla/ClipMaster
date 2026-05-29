package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shizuku 进程身份判断测试，保护覆盖安装后的旧进程退出策略。 */
class ShizukuProcessIdentityTest {

    @Test
    /** 当前进程名和 Provider 期望进程名完全一致时允许继续提交。 */
    fun verifyReturnsMatchedWhenProcessNamesAreEqual() {
        /** 当前进程完整名称。 */
        val currentProcessName = "com.cla.clip.master:shizuku_9_001234"
        /** Provider 返回的身份查询输出。 */
        val output = "Result: Bundle[{resultCode=ok, shizukuProcessName=$currentProcessName, connectRequested=true, reasonCode=identity_query}]"
        /** 待测身份协作者。 */
        val identity = ShizukuProcessIdentity(
            processNameReader = { currentProcessName },
            providerQuery = { ProviderCommandResult(exitCode = 0, output = output) }
        )

        /** 身份判断结果。 */
        val decision = identity.verify(eventId = "event-1")

        assertTrue(decision is ShizukuProcessIdentityDecision.Matched)
        assertEquals(ClipboardBridgeContract.REASON_PROCESS_MATCHED, decision.reasonCode)
        assertEquals(true, decision.connectRequested)
    }

    @Test
    /** 当前进程名和 Provider 期望进程名明确不一致时返回旧进程退出决策。 */
    fun verifyReturnsMismatchedWhenProcessNamesAreDifferent() {
        /** 当前旧 Shizuku 进程完整名称。 */
        val currentProcessName = "com.cla.clip.master:shizuku_8_001234"
        /** Provider 期望的新 Shizuku 进程完整名称。 */
        val expectedProcessName = "com.cla.clip.master:shizuku_9_001234"
        /** Provider 返回的身份查询输出。 */
        val output = "Result: Bundle[{resultCode=ok, shizukuProcessName=$expectedProcessName, connectRequested=true, reasonCode=identity_query}]"
        /** 待测身份协作者。 */
        val identity = ShizukuProcessIdentity(
            processNameReader = { currentProcessName },
            providerQuery = { ProviderCommandResult(exitCode = 0, output = output) }
        )

        /** 身份判断结果。 */
        val decision = identity.verify(eventId = "event-2")

        assertTrue(decision is ShizukuProcessIdentityDecision.Mismatched)
        assertEquals(ClipboardBridgeContract.REASON_PROCESS_MISMATCHED, decision.reasonCode)
        assertEquals(currentProcessName, decision.currentProcessName)
        assertEquals(expectedProcessName, decision.expectedProcessName)
    }

    @Test
    /** 当前进程名为空时仍会先查询 Provider，但最终只跳过提交不自杀。 */
    fun verifyReturnsUnknownWhenCurrentProcessNameMissing() {
        /** Provider 查询是否被调用，用于确认空本地进程名也会唤醒 app。 */
        var queryCalled = false
        /** Provider 期望的新 Shizuku 进程完整名称。 */
        val expectedProcessName = "com.cla.clip.master:shizuku_9_001234"
        /** Provider 返回的身份查询输出。 */
        val output = "Result: Bundle[{resultCode=ok, shizukuProcessName=$expectedProcessName, connectRequested=true, reasonCode=identity_query}]"
        /** 待测身份协作者。 */
        val identity = ShizukuProcessIdentity(
            processNameReader = { "" },
            providerQuery = {
                queryCalled = true
                ProviderCommandResult(exitCode = 0, output = output)
            }
        )

        /** 身份判断结果。 */
        val decision = identity.verify(eventId = "event-3")

        assertTrue(queryCalled)
        assertTrue(decision is ShizukuProcessIdentityDecision.Unknown)
        assertEquals(ClipboardBridgeContract.REASON_CURRENT_PROCESS_NAME_MISSING, decision.reasonCode)
    }

    @Test
    /** Provider 非 ok 或缺少进程名时身份不确定，只跳过提交。 */
    fun verifyReturnsUnknownWhenProviderDoesNotReturnExpectedProcessName() {
        /** 当前进程完整名称。 */
        val currentProcessName = "com.cla.clip.master:shizuku_9_001234"
        /** Provider 返回进程名缺失的身份查询输出。 */
        val output = "Result: Bundle[{resultCode=shizuku_process_missing, reasonCode=missing_expected_process_name, connectRequested=false}]"
        /** 待测身份协作者。 */
        val identity = ShizukuProcessIdentity(
            processNameReader = { currentProcessName },
            providerQuery = { ProviderCommandResult(exitCode = 0, output = output) }
        )

        /** 身份判断结果。 */
        val decision = identity.verify(eventId = "event-4")

        assertTrue(decision is ShizukuProcessIdentityDecision.Unknown)
        assertEquals(ClipboardBridgeContract.REASON_MISSING_EXPECTED_PROCESS_NAME, decision.reasonCode)
        assertEquals(false, decision.connectRequested)
    }

    @Test
    /** Provider 命令异常时身份不确定，避免误杀最新 Shizuku 进程。 */
    fun verifyReturnsUnknownWhenProviderQueryThrows() {
        /** 当前进程完整名称。 */
        val currentProcessName = "com.cla.clip.master:shizuku_9_001234"
        /** 待测身份协作者。 */
        val identity = ShizukuProcessIdentity(
            processNameReader = { currentProcessName },
            providerQuery = { error("provider unavailable") }
        )

        /** 身份判断结果。 */
        val decision = identity.verify(eventId = "event-5")

        assertTrue(decision is ShizukuProcessIdentityDecision.Unknown)
        assertEquals(ClipboardBridgeContract.REASON_PROVIDER_QUERY_FAILED, decision.reasonCode)
        assertEquals(currentProcessName, decision.currentProcessName)
    }
}
