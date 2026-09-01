package com.cla.clip.feature.torrent.search.provider.builtin.anilibria

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 基于固定上源 AniLibria API 的搜索 Provider；搜索最多扩展 15 个 release，
 * release torrent 并发任务仍由注入的共享网络客户端统一调度。
 */
internal class AniLibriaProvider(
    /** 功能内唯一网络边界，Provider 不创建独立 HTTP 客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
) : SearchProvider {
    /** 稳定 ID、域名、能力与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("anilibria"),
        displayName = "AniLibria",
        baseUrl = DISPLAY_BASE_URL,
        supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** AniLibria manifest 只允许 API 根域与展示页 www 域名。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("anilibria.top", "www.anilibria.top"))
    /** 站点 JSON 容错只允许未知 key，顶层类型仍由解析器严格校验。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 查询 release ID 并并发取回每个 release 的 torrent；空数组直接返回。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 搜索 URL 使用 HttpUrl 编码并先移除控制字符。 */
        val searchUrl = AniLibriaUrlBuilder.buildSearchUrl(query)
        urlPolicy.requireHttpsUrl(searchUrl)
        /** 搜索端点返回的原始 JSON，仅交给纯解析器。 */
        val responseJson = networkClient.getJson(searchUrl)
        /** 最多保留 15 个合法 release ID，缺失/非数字单条跳过。 */
        val releaseIds = parseJsonArray(responseJson)
            /** element 是当前 release 节点，非对象或缺 ID 都返回 null 以跳过单条。 */
            .mapNotNull { element -> (element as? JsonObject)?.longValue("id") }
            .take(MAX_RELEASES)
        return coroutineScope {
            /** 每个 release 拥有独立子任务，父搜索取消时全部向下取消。 */
            val releaseTasks = releaseIds.map { releaseId -> async { fetchReleaseTorrents(releaseId) } }
            releaseTasks.awaitAll().flatten()
        }
    }

    /** 取回单个 release 的 torrent 数组；顶层异常统一抛 ProviderParseException。 */
    private suspend fun fetchReleaseTorrents(releaseId: Long): List<Torrent> {
        /** release ID 由数字 JSON 产生，通过 path segment 构造以保持路径边界。 */
        val torrentsUrl = AniLibriaUrlBuilder.buildReleaseTorrentsUrl(releaseId)
        urlPolicy.requireHttpsUrl(torrentsUrl)
        /** 单 release torrent 端点的原始 JSON。 */
        val responseJson = networkClient.getJson(torrentsUrl)
        /** element 是当前 torrent JSON 节点，逐条容错不改变整批结构契约。 */
        return parseJsonArray(responseJson).mapNotNull { element ->
            /** 非对象单条按缺字段容错跳过，不拖垮整批。 */
            val torrentObject = element as? JsonObject ?: return@mapNotNull null
            parseTorrent(torrentObject)
        }
    }

    /** 将单条 JSON 映射为领域 Torrent；hash 或名称缺失时返回 null。 */
    private fun parseTorrent(torrentObject: JsonObject): Torrent? {
        /** 必须归一为 40 位小写 hex 的 infoHash。 */
        val infoHash = torrentObject.stringValue("hash")?.let(MagnetInfoHashParser::normalize) ?: return null
        /** release 子对象用于名称回退与详情 alias，缺失时不影响 label 路径。 */
        val releaseObject = torrentObject["release"] as? JsonObject
        /** release.name 子对象的 English/main 名称回退。 */
        val releaseNameObject = releaseObject?.get("name") as? JsonObject
        /** torrent label 优先，其次 English，最后 main；全缺时跳过。 */
        val torrentName = torrentObject.stringValue("label")
            ?: releaseNameObject?.stringValue("english")
            ?: releaseNameObject?.stringValue("main")
            ?: return null
        /** 字节数使用共用两位小数格式化，缺失时保持 null。 */
        val formattedSize = torrentObject.longValue("size")?.let(TorrentFileSizeFormatter::formatBytes)
        /** 原始 magnet 只在其 btih 可解析时返回，不影响已有 hash 结果。 */
        val magnetUri = torrentObject.stringValue("magnet")?.takeIf { uri -> MagnetInfoHashParser.parse(uri) != null }
        /** ISO-8601 创建时间转为 UTC epochMillis，失败时保持 null。 */
        val uploadEpochMillis = torrentObject.stringValue("created_at")?.let(ProviderDateParser::parseIsoEpochMillis)
        /** release alias 通过 path segment 编码生成受信详情地址。 */
        val descriptionPageUrl = releaseObject?.stringValue("alias")
            ?.let(AniLibriaUrlBuilder::buildReleaseDetailsUrl)
        return Torrent(
            infoHash = infoHash,
            name = torrentName,
            size = formattedSize,
            seeders = torrentObject.uintValue("seeders"),
            peers = torrentObject.uintValue("leechers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = Category.ANIME,
            descriptionPageUrl = descriptionPageUrl,
            magnetUri = magnetUri,
        )
    }

    /** 严格解析 JSON 顶层数组；语法或类型异常不传递底层 message。 */
    private fun parseJsonArray(rawJson: String): JsonArray = runCatching {
        json.parseToJsonElement(rawJson) as? JsonArray
    }.getOrNull() ?: throw ProviderParseException()

    /** 只读取 JSON 字符串原语，空值或其他类型返回 null。 */
    private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)
        /** primitive 必须是字符串原语，避免把数字或布尔值静默当作名称。 */
        ?.takeIf { primitive -> primitive.isString }
        ?.content
        ?.takeIf(String::isNotBlank)

    /** 只读取可表示为 Long 的 JSON 数字，类型不符返回 null。 */
    private fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** 只读取可表示为 UInt 的非负 JSON 数字，越界返回 null。 */
    private fun JsonObject.uintValue(key: String): UInt? = (this[key] as? JsonPrimitive)?.content?.toUIntOrNull()

    /** AniLibria 固定快照常量，变更需同步上源 manifest 审计。 */
    private companion object {
        /** AniLibria API 的 HTTPS 根地址。 */
        private const val API_BASE_URL = "https://anilibria.top/api/v1"
        /** AniLibria release 详情的 HTTPS 展示根地址。 */
        private const val DISPLAY_BASE_URL = "https://www.anilibria.top"
        /** 一次搜索最多扩展的 release 数，与固定上源一致。 */
        private const val MAX_RELEASES = 15
    }

    /** 集中 AniLibria URL 构造，保证查询和路径均由 HttpUrl 编码。 */
    private object AniLibriaUrlBuilder {
        /** 构造 release 搜索 URL，原始查询不通过字符串拼接。 */
        fun buildSearchUrl(query: String): String = API_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("app/search/releases")
            .addQueryParameter("query", sanitizeProviderQuery(query))
            .build()
            .toString()

        /** 构造单 release torrent 列表 URL，ID 作为独立 path segment。 */
        fun buildReleaseTorrentsUrl(releaseId: Long): String = API_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("anime/torrents/release")
            .addPathSegment(releaseId.toString())
            .build()
            .toString()

        /** 构造 release 详情 URL，alias 作为独立 path segment 以阻断路径注入。 */
        fun buildReleaseDetailsUrl(alias: String): String = DISPLAY_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("anime/releases/release")
            .addPathSegment(alias)
            .build()
            .toString()
    }
}
