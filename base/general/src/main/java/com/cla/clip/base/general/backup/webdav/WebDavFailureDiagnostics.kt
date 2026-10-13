package com.cla.clip.base.general.backup.webdav

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

/** cause 有界且按对象身份去重；只读类型，不读取可能包含地址或凭据的 message。 */
private fun failureEvidence(failure: Throwable): List<Throwable> {
    /** 有界原始证据，最多八层避免恶意循环或异常包装膨胀。 */
    val evidence = mutableListOf<Throwable>()
    /** 对象身份避免自定义 equals 干扰循环判断。 */
    val seen = IdentityHashMap<Throwable, Boolean>()
    /** 当前层仅在此调用内存中保留。 */
    var current: Throwable? = failure
    while (current != null && evidence.size < 8 && seen.put(current, true) == null) {
        evidence.add(current)
        current = current.cause
    }
    return evidence
}

/** 优先最深的具体类型，普通 IO 包装不能覆盖 DNS/TLS 等证据；套接字类型不猜 reset。 */
internal fun webDavFailureDetail(failure: Throwable): String = failureEvidence(failure).asReversed().firstNotNullOfOrNull { evidence ->
    when (evidence) {
        is UnknownHostException -> "dns_failed"
        is SocketTimeoutException -> "request_timeout"
        is SSLException -> "tls_failed"
        is ConnectException -> "connect_failed"
        is NoRouteToHostException -> "route_unavailable"
        is EOFException -> "unexpected_eof"
        is SocketException -> "socket_failed"
        else -> null
    }
} ?: if (failure is IOException) "io_failed" else "unknown"

/** 最终日志使用固定操作/阶段和有限类型链，绝不打印 Throwable、堆栈或异常原文。 */
internal fun webDavFailureLog(operation: String, phase: String, failure: Throwable): String {
    /** 只接受内部已知操作，未知自由文本不进入公共日志。 */
    val safeOperation = operation.takeIf { it in setOf("upload_file", "upload_text", "download_file", "delete_file", "directory_exists", "mkcol", "propfind", "list_backups") } ?: "unknown"
    /** 阶段仅区分请求发送与响应消费，不拼接第三方文本。 */
    val safePhase = if (phase == "response") "response" else "request"
    /** 类型名只作辅助，稳定诊断分类不依赖 R8 名称。 */
    val types = failureEvidence(failure).joinToString(">") { it.javaClass.simpleName.take(64) }
    return "WebDAV 请求异常 operation=$safeOperation phase=$safePhase detail=${webDavFailureDetail(failure)} types=$types"
}
