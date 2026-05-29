package com.cla.clip.base.hidden.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** 隐藏 API 豁免入口测试，保护 API 28 版本门控和空签名跳过契约。 */
class HiddenApiExemptionsTest {
    @Test
    /** Android 8.1 及更早没有隐藏 API 限制，封装必须跳过 HiddenApiBypass 调用。 */
    fun shouldAddSkipsBeforeAndroidP() {
        /** Android 8.1/API 27 的输入版本，用于保护 minSdk 24 到 27 的兼容分支。 */
        val apiLevel = 27

        assertEquals(false, HiddenApiExemptions.shouldAdd(apiLevel))
    }

    @Test
    /** Android P/API 28 及以上存在隐藏 API 限制，封装允许调用 HiddenApiBypass。 */
    fun shouldAddEnablesOnAndroidPAndAbove() {
        /** Android P/API 28 的输入版本，是隐藏 API 豁免能力的最低启用边界。 */
        val apiLevel = 28

        assertEquals(true, HiddenApiExemptions.shouldAdd(apiLevel))
    }

    @Test
    /** 空签名没有可豁免目标，即使运行在 Android P/API 28 也必须直接跳过。 */
    fun addIfNeededSkipsEmptySignatures() {
        assertEquals(false, HiddenApiExemptions.addIfNeeded())
    }
}
