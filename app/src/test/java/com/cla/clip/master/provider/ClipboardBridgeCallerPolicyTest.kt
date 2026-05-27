package com.cla.clip.master.provider

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 调用方 UID 校验测试，保护导出 Provider 不被普通 App 滥用。 */
class ClipboardBridgeCallerPolicyTest {
    @Test
    /** shell 和 root 是 Provider 实验通道允许的唯一调用方。 */
    fun isAllowedAcceptsShellAndRootOnly() {
        assertTrue(ClipboardBridgeCallerPolicy.isAllowed(Process.SHELL_UID))
        assertTrue(ClipboardBridgeCallerPolicy.isAllowed(Process.ROOT_UID))

        assertFalse(ClipboardBridgeCallerPolicy.isAllowed(10_000))
        assertFalse(ClipboardBridgeCallerPolicy.isAllowed(Process.SYSTEM_UID))
    }
}
