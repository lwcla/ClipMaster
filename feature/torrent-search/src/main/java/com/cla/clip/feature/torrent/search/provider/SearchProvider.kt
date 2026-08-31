package com.cla.clip.feature.torrent.search.provider

import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails

/** 固定 Provider 的稳定 ID 包装，避免将显示名称或类名当作协议。 */
@JvmInline
value class ProviderId(
    /** 写入日志和事件的稳定标识，不能为空的约束由注册表批次负责。 */
    val value: String,
)

/** Provider 元数据；展示名称可变化，id 和安全等级属于稳定调度契约。 */
data class ProviderDescriptor(
    /** Provider 稳定 ID，供结果、失败和日志关联。 */
    val id: String,
    /** 仅供 UI 展示的名称，不作为持久化 key。 */
    val displayName: String,
    /** 安全模式过滤所需等级。 */
    val safetyLevel: ProviderSafetyLevel,
)

/** Provider parser 的稳定失败信号；不接收站点正文或异常 message，避免跨层泄漏不可信内容。 */
class ProviderParseException : IllegalStateException()

/** 搜索 Provider 的最小能力接口；实现只能经构造注入可替换网络客户端。 */
interface SearchProvider {
    /** 用于安全筛选、事件关联和日志脱敏的固定描述符。 */
    val descriptor: ProviderDescriptor

    /** 执行单源搜索；异常由 SearchCoordinator 隔离并映射稳定失败原因。 */
    suspend fun search(query: String): List<Torrent>
}

/** 可选详情能力，避免所有 Provider 被迫实现未支持的详情请求。 */
interface DetailsProvider {
    /** 读取单个详情；地址只能在业务层传递，日志不得记录。 */
    suspend fun details(detailsUrl: String): TorrentDetails
}

/** 可选最新资源能力，后续浏览页按需调用。 */
interface LatestProvider {
    /** 读取最新资源；异常遵循与搜索相同的失败边界。 */
    suspend fun latest(): List<Torrent>
}

/** 可选热门资源能力，后续浏览页按需调用。 */
interface TopProvider {
    /** 读取热门资源；异常遵循与搜索相同的失败边界。 */
    suspend fun top(): List<Torrent>
}
