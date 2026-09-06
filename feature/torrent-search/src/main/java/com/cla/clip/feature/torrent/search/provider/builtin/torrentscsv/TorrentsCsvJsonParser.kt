package com.cla.clip.feature.torrent.search.provider.builtin.torrentscsv

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** TorrentsCSV 显式 key JSON parser；顶层错误失败，坏候选独立跳过或字段降级。 */
internal class TorrentsCsvJsonParser(
    /** 结果写入的稳定 Provider 身份。 */
    private val descriptor: ProviderDescriptor,
    /** JSON tree 与领域映射专用计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析 torrents 数组；合法空数组保持空结果。 */
    suspend fun parseResults(
        /** 受网络大小上限保护的 JSON 正文，禁止回显。 */
        response: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** root 必须是 JSON 对象。 */
        val root = parseObject(response)
        /** rows 必须是固定协议中的 torrents 数组。 */
        val rows = root["torrents"] as? JsonArray ?: throw ProviderParseException()
        /** row 是当前外部候选，协作取消优先于字段容错。 */
        rows.mapNotNullCancellable { row ->
            /** 非对象候选只跳过自身。 */
            val item = row as? JsonObject ?: return@mapNotNullCancellable null
            parseItem(item)
        }
    }

    /** 映射一个候选；名称和 hash 必填，统计与时间独立降级。 */
    private fun parseItem(
        /** 当前外部结果对象。 */
        item: JsonObject,
    ): Torrent? {
        /** name 只接受非空 JSON 字符串。 */
        val name = item.text("name")?.takeIf(String::isNotBlank) ?: return null
        /** infoHash 使用公共解析器固定大小写与长度。 */
        val infoHash = item.text("infohash")?.let(MagnetInfoHashParser::normalize) ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = item.number("size_bytes")?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = item.unsigned("seeders"),
            peers = item.unsigned("leechers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = item.number("created_unix")?.let(ProviderDateParser::epochSecondsToMillis),
            category = Category.OTHER,
        )
    }

    /** 将语法错误或非对象顶层转换为无正文稳定解析异常。 */
    private fun parseObject(
        /** 未信任 JSON 正文。 */
        response: String,
    ): JsonObject =
        (runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject)
            ?: throw ProviderParseException()

    /** 字符串字段不接受数字或布尔值的隐式转换。 */
    private fun JsonObject.text(
        /** 固定上源字段名。 */
        key: String,
    ): String? = (get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

    /** 整数字段只接受 JSON number 且以 Long 拒绝溢出。 */
    private fun JsonObject.number(
        /** 固定上源字段名。 */
        key: String,
    ): Long? = (get(key) as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull

    /** 人数只接受 UInt 范围内的 JSON 整数。 */
    private fun JsonObject.unsigned(
        /** 固定上源字段名。 */
        key: String,
    ): UInt? = number(key)
        ?.takeIf {
            /** value 是待校验的外部人数整数。 */
            value -> value in 0L..UInt.MAX_VALUE.toLong()
        }
        ?.toUInt()
}
