package com.cla.clip.base.general.backup.webdav

import android.os.SystemClock
import com.cla.clip.base.general.utils.logD
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.*

/** 每个 Call 独立观察 HTTP 阶段，绝不读取地址/头/正文或改变请求、取消及重试。 */
internal class WebDavRequestEventListener(
    /** 已净化的低敏关联标签，不持有完整请求。 */
    private val trace: WebDavRequestTrace,
    /** 可注入的单调毫秒时钟，包含休眠跨度但不能据此推断冻结。 */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** 复用统一日志门控，测试可以注入输出捕获。 */
    private val record: (String) -> Unit = { line -> logD("WebDavClient") { line } },
) : EventListener() {
    /** 当前 Call 起始时间，只在 callStart 事件设置。 */
    private var startedAt = 0L
    /** 最近阶段起始时间，缺失的 DNS/连接阶段不补造。 */
    private var phaseStartedAt = 0L
    /** 最近已观测阶段，仅来自本类固定常量。 */
    private var phase = "created"
    /** 请求体完整写入回调的字节计数，未知时保持 -1。 */
    private var requestBytes = -1L
    /** 终态只记录一次，取消后的迟到失败不会被重复计入。 */
    private var finished = false

    /** 生命周期起点不读取请求内容。 */
    override fun callStart(call: Call) = emit("started", "call_started", start = true)
    /** 只记录解析阶段，域名参数不进入状态或日志。 */
    override fun dnsStart(call: Call, domainName: String) = emit("phase", "dns")
    /** 解析结束后等待连接，解析结果不进入日志。 */
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = emit("phase", "dns_complete")
    /** 代理和地址参数完全忽略，避免暴露服务地址。 */
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = emit("phase", "connecting")
    /** TLS 仅记录阶段，不保留证书或握手内容。 */
    override fun secureConnectStart(call: Call) = emit("phase", "tls")
    /** 握手结束不输出对端身份。 */
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = emit("phase", "tls_complete")
    /** 连接就绪，复用连接没有该事件也不补造。 */
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = emit("phase", "connected")
    /** 连接失败保留当前阶段，不凭异常猜测系统限制，也不提前终结多地址连接过程。 */
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
        protocol: Protocol?, ioe: IOException) = emit("connect_failed")
    /** 请求头开始写入，不读取头值。 */
    override fun requestHeadersStart(call: Call) = emit("phase", "request_headers")
    /** 请求体开始发送，不采集正文或逐字节进度。 */
    override fun requestBodyStart(call: Call) = emit("phase", "request_body")
    /** 完整写入后记录字节数并等待响应；不声称服务端已成功保存。 */
    override fun requestBodyEnd(call: Call, byteCount: Long) = emit("phase", "waiting_response", bytes = byteCount)
    /** OkHttp 即将读取响应头，该事件不表示已经收到完整响应。 */
    override fun responseHeadersStart(call: Call) = emit("phase", "waiting_response")
    /** 完整响应头已读取，仅阶段改变，不读取头值或正文。 */
    override fun responseHeadersEnd(call: Call, response: Response) = emit("phase", "response_received")
    /** 请求生命周期完成，不等于备份结果已持久化。 */
    override fun callEnd(call: Call) = emit("finished", terminal = true)
    /** 晚到 IO 在取消之后只能记为取消，不输出异常 message。 */
    override fun callFailed(call: Call, ioe: IOException) = emit(if (call.isCanceled()) "cancelled" else "failed", terminal = true)
    /** 立即标记取消，后续阶段/失败事件不产生第二终态。 */
    override fun canceled(call: Call) = emit("cancelled", terminal = true)

    /** 串行归约回调，观察器/时钟异常均隔离；只输出固定阶段、数量和单调耗时。 */
    @Synchronized private fun emit(event: String, nextPhase: String? = null, terminal: Boolean = false,
        start: Boolean = false, bytes: Long? = null) {
        if (finished) return
        try {
            /** 当前单调时间以及前一阶段，转换时输出其完整耗时。 */
            val now = clock()
            val previousPhase = phase
            val previousDuration = (now - phaseStartedAt).coerceAtLeast(0L)
            if (start) { startedAt = now; phaseStartedAt = now }
            if (nextPhase != null && nextPhase != phase) { phase = nextPhase; phaseStartedAt = now }
            if (bytes != null) requestBytes = bytes.coerceAtLeast(0L)
            if (terminal) finished = true
            record("WebDAV 请求阶段 ${trace.fields()} event=$event phase=$phase " +
                "elapsedMs=${(now - startedAt).coerceAtLeast(0L)} phaseElapsedMs=${(now - phaseStartedAt).coerceAtLeast(0L)} " +
                "previousPhase=$previousPhase previousPhaseElapsedMs=${if (start) 0L else previousDuration} requestBytes=$requestBytes")
        } catch (_: Exception) {
            // 尽力诊断不能改变真实传输、结果或取消；也不能递归打印接收器异常。
            if (terminal) finished = true
        }
    }
}
