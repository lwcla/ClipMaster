package com.cla.clip.feature.torrent.search.logging

import android.util.Log
import com.cla.clip.feature.torrent.search.model.contract.ProviderFailureReason
import com.cla.clip.feature.torrent.search.provider.ProviderId

/** Provider 生命周期阶段稳定协议，日志输出只使用显式 code。 */
enum class ProviderLogStage(
    /** 对外稳定阶段 code。 */
    val code: String,
) {
    /** Provider 已开始执行。 */
    STARTED("started"),
    /** Provider 产生一批结果。 */
    RESULTS("results"),
    /** Provider 因安全策略被跳过。 */
    SKIPPED("skipped"),
    /** Provider 执行失败。 */
    FAILED("failed"),
    /** Provider 正常完成。 */
    COMPLETED("completed"),
    /** 兼容未来未知阶段，不用于正常写入。 */
    UNKNOWN("unknown");

    /** 集中提供阶段 code 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；非法值回退 unknown。 */
        fun fromCode(code: String?): ProviderLogStage = entries.firstOrNull { stage -> stage.code == code } ?: UNKNOWN
    }
}

/** Provider 操作类型稳定协议；日志只输出固定 code，不接受自由文本模式。 */
enum class ProviderLogOperation(
    /** 低敏操作 code。 */
    val code: String,
) {
    /** 关键词搜索。 */
    SEARCH("search"),
    /** 最新资源浏览。 */
    LATEST("latest"),
    /** 热门资源浏览。 */
    TOP("top"),
    /** 原生详情读取。 */
    DETAILS("details"),
}

/** Provider 生命周期低敏日志事件；封闭类型和字段白名单共同限制敏感信息入口。 */
data class ProviderLogEvent(
    /** 强类型稳定 Provider ID，不能填入地址、查询或展示内容。 */
    val providerId: ProviderId,
    /** 固定操作类型，默认搜索以保持既有调用方契约。 */
    val operation: ProviderLogOperation = ProviderLogOperation.SEARCH,
    /** 封闭生命周期阶段。 */
    val stage: ProviderLogStage,
    /** 当前批次结果数量；无法统计时为空。 */
    val resultCount: Int?,
    /** 当前阶段耗时毫秒；无法统计时为空。 */
    val durationMs: Long?,
    /** HTTP 状态码；没有响应时为空。 */
    val httpStatusCode: Int?,
    /** 当前操作是否已经发生重试；尚未执行重试或不适用时为空/false。 */
    val retried: Boolean?,
    /** 封闭失败原因；成功阶段为空。 */
    val reason: ProviderFailureReason?,
)

/** 可替换日志出口，测试与宿主只观察封闭事件而不接触请求/响应。 */
fun interface ProviderLogger {
    /** 输出一个已脱敏事件；实现不得追加异常 message 或网络内容。 */
    fun log(event: ProviderLogEvent)
}

/** 把封闭事件格式化为 Android 日志文本；只展开稳定 code 和低敏数值。 */
internal fun ProviderLogEvent.toAndroidLogMessage(): String =
    "providerId=${providerId.value} operationCode=${operation.code} stage=${stage.code} resultCount=$resultCount durationMs=$durationMs " +
        "httpStatusCode=$httpStatusCode retried=$retried reasonCode=${reason?.code}"

/** 默认 Android 日志实现，只输出 ProviderLogEvent 的固定白名单字段。 */
class AndroidProviderLogger : ProviderLogger {
    /** 输出固定字段顺序的调试日志，绝不输出 URL、查询、header、Cookie、Key、正文或异常 message。 */
    override fun log(event: ProviderLogEvent) {
        /** 固定 TAG 只表达模块职责。 */
        val tag = "TorrentSearch"
        /** 格式化结果只可能来自封闭事件类型。 */
        val message = event.toAndroidLogMessage()
        Log.d(tag, message)
    }
}
