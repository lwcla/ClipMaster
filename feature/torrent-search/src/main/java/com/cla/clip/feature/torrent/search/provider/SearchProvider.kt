package com.cla.clip.feature.torrent.search.provider

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails

/** Provider 稳定 ID 包装；构造时校验持久化、事件和日志共同依赖的字符及长度边界。 */
@JvmInline
value class ProviderId(
    /** 小写 ASCII 分段标识，长度为 1..64，禁止 URL、空白和显示名称进入稳定协议。 */
    val value: String,
) {
    /** 在对象进入描述符、结果或日志前拒绝不稳定 ID。 */
    init {
        require(value.length in 1..MAX_LENGTH && STABLE_ID_PATTERN.matches(value))
    }

    /** Provider ID 校验常量集中入口。 */
    companion object {
        /** 稳定 ID 最大字符数，足以容纳内置与 Torznab 本地标识且限制日志膨胀。 */
        private const val MAX_LENGTH = 64
        /** 只允许小写字母、数字及单下划线分段。 */
        private val STABLE_ID_PATTERN = Regex("^[a-z0-9]+(?:_[a-z0-9]+)*$")
    }
}

/** Provider 来源类型稳定协议。 */
enum class ProviderType(
    /** 对外稳定来源 code。 */
    val code: String,
) {
    /** 随应用版本发布并经过审查的内置 Provider。 */
    BUILTIN("builtin"),
    /** 用户本地配置的 Torznab Provider。 */
    TORZNAB("torznab");

    /** 集中提供来源 code 的兼容读取。 */
    companion object {
        /** 精确匹配稳定 code；非法值回退内置，避免把未知配置当作外部凭据来源。 */
        fun fromCode(code: String?): ProviderType = entries.firstOrNull { type -> type.code == code } ?: BUILTIN
    }
}

/** Provider 完整元数据；网络调度、安全策略、能力页面和默认启用状态只读取此契约。 */
data class ProviderDescriptor(
    /** Provider 强类型稳定 ID。 */
    val id: ProviderId,
    /** 可本地化或随版本调整的展示名。 */
    val displayName: String,
    /** Provider 基础地址；内置来源必须由注册表保证 HTTPS。 */
    val baseUrl: String,
    /** 可选 Cloudflare 求解地址；为空时使用基础地址。 */
    val cloudflareSolverUrl: String? = null,
    /** Provider 支持或能标注的分类集合。 */
    val supportedCategories: Set<Category> = emptySet(),
    /** 安全模式过滤使用的等级。 */
    val safetyLevel: ProviderSafetyLevel,
    /** 新安装时是否默认启用；不安全来源必须由注册表设为 false。 */
    val enabledByDefault: Boolean,
    /** 内置或 Torznab 来源类型。 */
    val type: ProviderType = ProviderType.BUILTIN,
    /** Provider 是否需要 Cloudflare 解锁流程。 */
    val isCloudflareProtected: Boolean = false,
)

/** Provider parser 的稳定失败信号；不携带站点正文或异常 message。 */
class ProviderParseException : IllegalStateException()

/** 搜索 Provider 基础能力；实现只能经构造注入可替换网络客户端。 */
interface SearchProvider {
    /** 完整稳定描述符，供安全筛选、事件关联和能力展示。 */
    val descriptor: ProviderDescriptor

    /** 按查询与分类执行单源搜索；异常由 SearchCoordinator 隔离并稳定分类。 */
    suspend fun search(query: String, category: Category): List<Torrent>
}

/** 支持详情读取的 Provider 能力，继承基础搜索契约。 */
interface TorrentDetailsProvider : SearchProvider {
    /** 允许承载详情页的备用 host，仅存 host 而非完整 URL。 */
    val alternateUrlDomains: List<String> get() = emptyList()

    /** 从详情地址解析完整数据；不支持或无有效详情时返回 null。 */
    suspend fun getDetails(detailsPageUrl: String): TorrentDetails?
}

/** 支持最新资源浏览的 Provider 能力，继承基础搜索契约。 */
interface LatestTorrentsProvider : SearchProvider {
    /** 按分类读取最新资源；默认不限定分类。 */
    suspend fun getLatestTorrents(category: Category = Category.ALL): List<Torrent>
}

/** 支持热门资源浏览的 Provider 能力，继承基础搜索契约。 */
interface TopTorrentsProvider : SearchProvider {
    /** 按分类读取热门资源；默认不限定分类。 */
    suspend fun getTopTorrents(category: Category = Category.ALL): List<Torrent>
}
