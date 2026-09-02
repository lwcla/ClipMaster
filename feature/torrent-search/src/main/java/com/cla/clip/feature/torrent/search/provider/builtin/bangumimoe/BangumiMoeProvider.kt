package com.cla.clip.feature.torrent.search.provider.builtin.bangumimoe

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 固定快照 BangumiMoe JSON Provider；网络留在调用协程，JSON tree 与映射进入计算调度器。 */
internal class BangumiMoeProvider(
    /** 功能内共享网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON tree 解析和逐条领域映射使用的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, LatestTorrentsProvider, TorrentDetailsProvider {
    /** BangumiMoe 稳定元数据与 manifest SAFE、默认关闭结论。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("bangumimoe"),
        displayName = "BangumiMoe",
        baseUrl = BASE_URL,
        supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** manifest 只允许 bangumi.moe 精确 host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("bangumi.moe"))
    /** 搜索/latest 解析器不持有网络能力。 */
    private val resultsParser = BangumiMoeJsonParser(descriptor, parsingDispatcher)

    /** 以结构化 JSON `query` POST 搜索，用户输入先删除控制字符。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 固定搜索接口在发起网络前通过精确 host 校验。 */
        val requestUrl = urlPolicy.requireHttpsUrl("$BASE_URL/api/v2/torrent/search").toString()
        /** payload 由 kotlinx serialization 生成转义，禁止手拼用户输入。 */
        val payload = Json.encodeToString(
            buildJsonObject { put("query", JsonPrimitive(sanitizeProviderQuery(query))) },
        )
        /** 响应原文仅在内存交给 parser。 */
        val responseJson = networkClient.postJson(requestUrl, payload)
        return resultsParser.parseResults(responseJson)
    }

    /** latest 使用固定 GET JSON 端点。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> {
        /** 经白名单验证的 latest URL。 */
        val requestUrl = urlPolicy.requireHttpsUrl("$BASE_URL/api/torrent/latest").toString()
        /** 响应原文不进入日志。 */
        val responseJson = networkClient.getJson(requestUrl)
        return resultsParser.parseResults(responseJson)
    }

    /** 从同 host 精确 `/torrent/{id}` 提取 ID 后 POST 详情接口。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 详情输入先完成 scheme/host 验证。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl)
        if (trustedDetailsUrl.query != null || trustedDetailsUrl.fragment != null) throw HttpDeniedException()
        /** 解码 path segment 必须恰好 torrent 与一个非空 ID。 */
        val pathSegments = trustedDetailsUrl.pathSegments
        if (pathSegments.size != 2 || pathSegments[0] != "torrent" || pathSegments[1].isBlank()) {
            throw HttpDeniedException()
        }
        /** 结构化路径提取出的解码 ID，不使用 takeLastWhile 猜测。 */
        val torrentId = pathSegments[1]
        /** 固定 fetch 接口通过相同白名单。 */
        val requestUrl = urlPolicy.requireHttpsUrl("$BASE_URL/api/torrent/fetch").toString()
        /** `_id` payload 由 JSON encoder 负责转义。 */
        val payload = Json.encodeToString(buildJsonObject { put("_id", JsonPrimitive(torrentId)) })
        /** 详情 JSON 原文只在内存解析。 */
        val responseJson = networkClient.postJson(requestUrl, payload)
        return resultsParser.parseDetails(responseJson)
    }

    /** BangumiMoe 固定根地址。 */
    private companion object {
        /** manifest 审计通过的唯一 HTTPS 根地址。 */
        private const val BASE_URL = "https://bangumi.moe"
    }
}

/** BangumiMoe JSON tree parser；严格顶层结构，单条字段错误按缺失处理。 */
private class BangumiMoeJsonParser(
    /** Torrent/TorrentDetails 稳定 Provider 字段。 */
    private val descriptor: ProviderDescriptor,
    /** JSON 解析与领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 搜索/latest 必须是包含 torrents 数组的对象；合法空数组返回空列表。 */
    suspend fun parseResults(rawJson: String): List<Torrent> = withContext(parsingDispatcher) {
        /** 顶层 JSON 解析失败统一转为无 message 的稳定异常。 */
        val root = parseJson(rawJson) as? JsonObject ?: throw ProviderParseException()
        /** torrents 缺失或类型错误代表协议结构异常。 */
        val torrents = root["torrents"] as? JsonArray ?: throw ProviderParseException()
        /** element 是当前 torrents 数组元素，逐条响应协程取消。 */
        torrents.mapNotNullCancellable { element ->
            /** 单条必须是对象，否则仅跳过。 */
            val torrentObject = element as? JsonObject ?: return@mapNotNullCancellable null
            parseTorrent(torrentObject)
        }
    }

    /** 详情顶层必须是对象；必填字段缺失或 btih 冲突返回 null。 */
    suspend fun parseDetails(rawJson: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** 非对象详情响应属于顶层协议异常。 */
        val objectValue = parseJson(rawJson) as? JsonObject ?: throw ProviderParseException()
        /** 名称和 magnet 是详情必填。 */
        val name = objectValue.stringOrNull("title")?.takeIf(String::isNotBlank) ?: return@withContext null
        /** magnet 既用于展示也用于稳定 btih。 */
        val magnetUri = objectValue.stringOrNull("magnet")?.takeIf(String::isNotBlank) ?: return@withContext null
        /** magnet hash 必须合法。 */
        val magnetInfoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** 可选 infohash 存在时必须合法且与 magnet 一致。 */
        val declaredInfoHash = objectValue.stringOrNull("infohash")?.let(MagnetInfoHashParser::normalize)
        if (objectValue.containsKey("infohash") && declaredInfoHash == null) return@withContext null
        if (declaredInfoHash != null && declaredInfoHash != magnetInfoHash) return@withContext null
        TorrentDetails(
            infoHash = magnetInfoHash,
            name = name,
            size = objectValue.stringOrNull("size"),
            seeders = objectValue.uintOrNull("seeders"),
            peers = objectValue.uintOrNull("leechers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = objectValue.stringOrNull("publish_time")?.let(ProviderDateParser::parseIsoEpochMillis),
            category = Category.ANIME,
            magnetUri = magnetUri,
        )
    }

    /** 将单个 JSON 对象映射为 Torrent；必填字段错误返回 null。 */
    private fun parseTorrent(objectValue: JsonObject): Torrent? {
        /** 结果名称为必填字符串。 */
        val name = objectValue.stringOrNull("title")?.takeIf(String::isNotBlank) ?: return null
        /** magnet 为必填字符串。 */
        val magnetUri = objectValue.stringOrNull("magnet")?.takeIf(String::isNotBlank) ?: return null
        /** magnet 中的 btih 是稳定主键基准。 */
        val magnetInfoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        /** 可选显式 hash 存在时必须能规范化。 */
        val declaredInfoHash = objectValue.stringOrNull("infohash")?.let(MagnetInfoHashParser::normalize)
        if (objectValue.containsKey("infohash") && declaredInfoHash == null) return null
        if (declaredInfoHash != null && declaredInfoHash != magnetInfoHash) return null
        /** 详情 ID 只接受非空字符串，并由 HttpUrl path segment 编码。 */
        val torrentId = objectValue.stringOrNull("_id")?.takeIf(String::isNotBlank)
        /** 详情页面地址不存在时保持 null。 */
        val detailsPageUrl = torrentId?.let { id ->
            "https://bangumi.moe".toHttpUrl().newBuilder()
                .addPathSegment("torrent")
                .addPathSegment(id)
                .build()
                .toString()
        }
        return Torrent(
            infoHash = magnetInfoHash,
            name = name,
            size = objectValue.stringOrNull("size"),
            seeders = objectValue.uintOrNull("seeders"),
            peers = objectValue.uintOrNull("leechers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = objectValue.stringOrNull("publish_time")?.let(ProviderDateParser::parseIsoEpochMillis),
            category = Category.ANIME,
            descriptionPageUrl = detailsPageUrl,
            magnetUri = magnetUri,
        )
    }

    /** 解析原始 JSON；语法错误只抛稳定异常且不回显正文/message。 */
    private fun parseJson(rawJson: String): JsonElement = runCatching { Json.parseToJsonElement(rawJson) }
        .getOrElse { throw ProviderParseException() }
}

/** 读取可选 JSON 字符串；非 primitive 或非字符串类型统一按缺失。 */
private fun JsonObject.stringOrNull(key: String): String? {
    /** 值必须是非 null 的 JSON string，数字/布尔不做宽松转换。 */
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}

/** 读取可选非负 UInt；字符串和数字 primitive 均按十进制严格转换。 */
private fun JsonObject.uintOrNull(key: String): UInt? {
    /** primitive 缺失、null、负数或溢出均返回 null。 */
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.toUIntOrNull()
}
