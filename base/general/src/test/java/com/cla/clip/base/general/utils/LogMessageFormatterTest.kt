package com.cla.clip.base.general.utils

import org.junit.Assert.*
import org.junit.Test

/** 捕获许可放行 DEBUG 时也不能把地址和授权 URI 原文写到公共文件。 */
class LogMessageFormatterTest {
    /** 只用合成地址验证令牌、路径和参数不随日志保存。 */
    @Test fun removesUrlAndUriTokensWhileKeepingFailureEvidence() {
        /** 合成诊断包含大小写 scheme 和无空格的多种地址。 */
        val input = "reasonCode=dns_failed url=https://synthetic.invalid/private?secret=synthetic HTTP://synthetic.invalid/file uri=content://synthetic/private file://synthetic/private type=UnknownHostException"
        /** 只允许固定诊断字段保留，完整地址统一省略。 */
        val output = formatLogMessage(input)
        assertTrue(output.contains("reasonCode=dns_failed"))
        assertTrue(output.contains("type=UnknownHostException"))
        assertFalse(output.contains("synthetic"))
        assertEquals("pid=123 count=20", formatLogMessage("pid=123 count=20"))
    }
}
