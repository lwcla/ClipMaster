package com.cla.clip.feature.torrent.search.provider.builtin.anilibria

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** AniLibria JSON 顶层校验、逐条容错与领域映射 parser；不持有网络能力。 */
internal class AniLibriaJsonParser(
    /** 产出 Torrent 所需的稳定 Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** JSON DOM 与大列表循环使用的可注入计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** 站点 JSON 容错只允许未知 key，顶层类型仍由 parser 严格校验。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 在计算调度器提取最多 15 个合法 release ID，并在逐条循环中协作响应取消。 */
    suspend fun parseReleaseIds(rawJson: String): List<Long> = withContext(parsingDispatcher) {
        /** 严格解析后的 release 顶层数组。 */
        val releaseArray = parseJsonArray(rawJson)
        /** 按上源顺序收集且最多包含 15 个合法数字 ID。 */
        val releaseIds = mutableListOf<Long>()
        /** element 是当前 release 节点，循环每条都检查父协程取消。 */
        for (element in releaseArray) {
            currentCoroutineContext().ensureActive()
            /** 当前对象的数字 ID；非对象或缺 ID 按单条容错跳过。 */
            val releaseId = (element as? JsonObject)?.longValue("id") ?: continue
            releaseIds += releaseId
            if (releaseIds.size == MAX_RELEASES) break
        }
        releaseIds
    }

    /** 在计算调度器解析 torrent 数组，逐条缺字段跳过且大列表可协作取消。 */
    suspend fun parseReleaseTorrents(rawJson: String): List<Torrent> = withContext(parsingDispatcher) {
        /** 严格解析后的 torrent 顶层数组。 */
        val torrentArray = parseJsonArray(rawJson)
        /** element 是当前 torrent 节点，共用协作遍历会在每轮映射前响应取消。 */
        torrentArray.mapNotNullCancellable { element ->
            /** 非对象节点按单条缺字段容错跳过。 */
            val torrentObject = element as? JsonObject ?: return@mapNotNullCancellable null
            parseTorrent(torrentObject)
        }
    }

    /** 将单条 JSON 映射为领域 Torrent；hash 或名称缺失时返回 null。 */
    private fun parseTorrent(torrentObject: JsonObject): Torrent? {
        /** 必须归一为 40 位小写 hex 的 infoHash。 */
        val infoHash = torrentObject.stringValue("hash")?.let(MagnetInfoHashParser::normalize) ?: return null
        /** release 子对象用于名称回退与详情 alias，缺失时不影响 label 路径。 */
        val releaseObject = torrentObject["release"] as? JsonObject
        /** release.name 子对象提供 English/main 名称回退。 */
        val releaseNameObject = releaseObject?.get("name") as? JsonObject
        /** torrent label 优先，其次 English，最后 main；全缺时跳过。 */
        val torrentName = torrentObject.stringValue("label")
            ?: releaseNameObject?.stringValue("english")
            ?: releaseNameObject?.stringValue("main")
            ?: return null
        /** 字节数使用共用两位小数格式化，缺失时保持 null。 */
        val formattedSize = torrentObject.longValue("size")?.let(TorrentFileSizeFormatter::formatBytes)
        /** 原始 magnet 只在 btih 可解析时返回，不影响已有 hash 结果。 */
        val magnetUri = torrentObject.stringValue("magnet")?.takeIf { uri -> MagnetInfoHashParser.parse(uri) != null }
        /** ISO-8601 创建时间转为 UTC epochMillis，失败时保持 null。 */
        val uploadEpochMillis = torrentObject.stringValue("created_at")?.let(ProviderDateParser::parseIsoEpochMillis)
        /** release alias 通过独立 builder 编码生成受信详情地址。 */
        val descriptionPageUrl = releaseObject?.stringValue("alias")?.let(AniLibriaUrlBuilder::buildReleaseDetailsUrl)
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

    /** AniLibria 固定快照解析常量。 */
    private companion object {
        /** 一次搜索最多扩展的 release 数，与固定上源一致。 */
        private const val MAX_RELEASES = 15
    }
}
