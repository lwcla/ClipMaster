package com.cla.clip.base.general.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 公共日志保留代码栈证据，不能通过 Throwable message 泄露敏感外部文本。 */
class LogThrowableFormatterTest {
    /** 合成包装与文件名验证正文去除 message 和绝对目录。 */
    @Test fun keepsCodeFramesWithoutExceptionMessagesOrDirectories() {
        /** 合成敏感文本只用于断言不存在，不是真实凭据。 */
        val cause = IOException("synthetic-private-message")
        cause.stackTrace = arrayOf(StackTraceElement("Synthetic", "read", "/private/synthetic/Source.kt", 7))
        /** 第二层 message 同样不得被 Throwable.toString 带出。 */
        val failure = IllegalStateException("synthetic-private-message", cause)
        /** 最终用于日志的格式化结果。 */
        val output = formatLogThrowable(failure)
        assertFalse(output.contains("synthetic-private-message"))
        assertFalse(output.contains("/private/"))
        assertTrue(output.contains("Synthetic.read(Source.kt:7)"))
        assertTrue(output.contains("IOException"))
    }
}
