package com.cla.clip.feature.torrent.search.logging

import android.util.Log

/** Provider 生命周期的低敏结构化日志事件；字段白名单由单测锁定。 */
data class ProviderLogEvent(
    /** 稳定 Provider ID；禁止填入站点地址或显示内容。 */
    val providerId: String,
    /** 当前生命周期阶段，例如 started、results、failed 或 completed。 */
    val stage: String,
    /** 当前批次结果数量；无法统计时为 null。 */
    val resultCount: Int?,
    /** 当前阶段耗时，单位毫秒；无法统计时为 null。 */
    val durationMs: Long?,
    /** HTTP 状态码；不存在 HTTP 响应时为 null。 */
    val httpStatusCode: Int?,
    /** 当前失败是否可由调用方重试。 */
    val retryable: Boolean?,
    /** 稳定失败 reasonCode；成功阶段为 null。 */
    val reasonCode: String?,
)

/** 可替换日志出口，测试与未来宿主可以验证事件而不接触 Android Log。 */
fun interface ProviderLogger {
    /** 输出一个已脱敏事件；调用方不得向事件外拼接请求或响应内容。 */
    fun log(event: ProviderLogEvent)
}

/** 默认 Android 日志实现，仅序列化 ProviderLogEvent 的白名单字段。 */
class AndroidProviderLogger : ProviderLogger {
    /** 输出固定字段顺序的调试日志；绝不输出 URL、查询、header、Cookie、payload 或正文。 */
    override fun log(event: ProviderLogEvent) {
        /** 固定 TAG 便于按模块过滤，而不包含用户或 Provider 私密内容。 */
        val tag = "TorrentSearch"
        /** 仅由 allowlist 字段拼成的低敏文本，避免 Throwable message 泄漏请求数据。 */
        val message = "providerId=${event.providerId} stage=${event.stage} resultCount=${event.resultCount} durationMs=${event.durationMs} httpStatusCode=${event.httpStatusCode} retryable=${event.retryable} reasonCode=${event.reasonCode}"
        Log.d(tag, message)
    }
}
