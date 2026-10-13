package com.cla.clip.base.general.backup.webdav

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** 诊断只按异常证据分类，禁止完整异常 message 泄露到公共日志。 */
class WebDavFailureDiagnosticsTest {
    /** 请求和嵌套 IO 都应保留明确类别，套接字异常不能凭类型猜重置。 */
    @Test fun distinguishesTransportEvidenceAndNestedCauses() {
        assertEquals("dns_failed", webDavFailureDetail(UnknownHostException("synthetic-private")))
        assertEquals("connect_failed", webDavFailureDetail(ConnectException()))
        assertEquals("request_timeout", webDavFailureDetail(SocketTimeoutException()))
        assertEquals("tls_failed", webDavFailureDetail(SSLHandshakeException("synthetic-private")))
        assertEquals("unexpected_eof", webDavFailureDetail(IOException(EOFException())))
        assertEquals("socket_failed", webDavFailureDetail(SocketException("synthetic-private")))
    }

    /** 日志字段不保留 message，自由文本操作名也不能进入公共文件。 */
    @Test fun safeLogRejectsExternalTextAndBoundsCyclicCauses() {
        /** 合成循环异常用于保护有界遍历。 */
        val failure = IOException("synthetic-private")
        /** 第二层包装与第一层互相引用，不应死循环。 */
        val wrapper = IOException("synthetic-private", failure)
        failure.initCause(wrapper)
        /** 测试 formatter 的最终输出，而非检查源码字符串。 */
        val message = webDavFailureLog("synthetic-private", "request", failure)
        assertFalse(message.contains("synthetic-private"))
        assertTrue(message.contains("operation=unknown"))
        assertTrue(message.length < 600)
    }
}
