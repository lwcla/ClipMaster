package com.cla.clip.master.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shizuku 连接器纯规则测试，保护覆盖安装后的 bind 跳过条件。 */
class ShizukuConnectorTest {

    @Test
    /** binder 活着且绑定进程名等于最新期望进程名时才允许跳过 bind。 */
    fun shouldSkipBindOnlyWhenAliveAndProcessNameMatches() {
        /** 最新期望 Shizuku 完整进程名。 */
        val expectedProcessName = "com.cla.clip.master:shizuku_10_001234"

        assertTrue(
            ShizukuConnector.shouldSkipBind(
                isAlive = true,
                boundProcessName = expectedProcessName,
                expectedProcessName = expectedProcessName
            )
        )
        assertFalse(
            ShizukuConnector.shouldSkipBind(
                isAlive = true,
                boundProcessName = "com.cla.clip.master:shizuku_9_001234",
                expectedProcessName = expectedProcessName
            )
        )
        assertFalse(
            ShizukuConnector.shouldSkipBind(
                isAlive = false,
                boundProcessName = expectedProcessName,
                expectedProcessName = expectedProcessName
            )
        )
        assertFalse(
            ShizukuConnector.shouldSkipBind(
                isAlive = null,
                boundProcessName = expectedProcessName,
                expectedProcessName = expectedProcessName
            )
        )
    }
}
