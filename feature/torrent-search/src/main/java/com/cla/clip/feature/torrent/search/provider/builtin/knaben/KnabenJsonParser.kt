package com.cla.clip.feature.torrent.search.provider.builtin.knaben

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

/** Knaben 显式 key JSON parser；顶层失败与单条容错保持不同契约。 */
internal class KnabenJsonParser(
    /** Provider 稳定身份字段来源。 */ private val descriptor: ProviderDescriptor,
    /** 可选详情链接的安全策略。 */ private val urlPolicy: KnabenUrlPolicy,
    /** JSON 和领域映射专用计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** hits 必须为数组；合法空数组返回空列表，非对象或坏必填单条跳过。 */
    suspend fun parseResults(
        /** 有大小上限的响应正文，禁止进入日志与异常。 */ response: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** root 必须是 JSON 对象。 */
        val root = runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject ?: throw ProviderParseException()
        /** rows 是 Knaben 固定 hits 数组。 */
        val rows = root["hits"] as? JsonArray ?: throw ProviderParseException()
        /** row 是当前外部候选，协作取消优先于字段容错。 */
        rows.mapNotNullCancellable { row -> parseItem(row as? JsonObject ?: return@mapNotNullCancellable null) }
    }

    /** 解析一条结果；标题和 magnet 是必填身份，其他字段独立降级。 */
    private fun parseItem(
        /** 当前结果对象。 */ item: JsonObject,
    ): Torrent? {
        /** name 只接受非空 JSON 字符串。 */ val name = item.text("title")?.takeIf(String::isNotBlank) ?: return null
        /** magnetUri 必须包含可规范化 btih。 */ val magnetUri = item.text("magnetUrl") ?: return null
        /** infoHash 是领域稳定身份。 */ val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        return Torrent(
            infoHash = infoHash, name = name, size = item.long("bytes")?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = item.uint("seeders"), peers = item.uint("peers"), providerId = descriptor.id, providerName = descriptor.displayName,
            uploadEpochMillis = item.text("date")?.let(ProviderDateParser::parseIsoEpochMillis),
            descriptionPageUrl = urlPolicy.optionalDetailsUrl(item.text("details")), magnetUri = magnetUri,
            category = mapCategory(item["categoryId"] as? JsonArray),
        )
    }

    /** 最小有效数字 categoryId 决定完整百万区间，未知值回退 ALL。 */
    private fun mapCategory(
        /** 上源 categoryId 数组；缺失或无有效整数时为空。 */ categoryIds: JsonArray?,
    ): Category {
        /** minimumId 忽略 null、字符串、小数和溢出值。 */
        val minimumId = categoryIds?.mapNotNull { element ->
            /** primitive 必须是 JSON 数字，字符串数字不属于固定协议。 */
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            primitive.takeUnless(JsonPrimitive::isString)?.longOrNull
        }?.minOrNull() ?: return Category.ALL
        return when (minimumId) {
            in 1_000_000L..1_999_999L -> Category.MUSIC; in 2_000_000L..2_999_999L -> Category.SERIES
            in 3_000_000L..3_999_999L -> Category.MOVIES; in 4_000_000L..4_999_999L -> Category.APPS
            in 5_000_000L..5_999_999L -> Category.PORN; in 6_000_000L..6_999_999L -> Category.ANIME
            in 7_000_000L..7_999_999L -> Category.GAMES; in 9_000_000L..9_999_999L -> Category.BOOKS
            in 10_000_000L..10_999_999L -> Category.OTHER; else -> Category.ALL
        }
    }

    /** 字符串字段不接受其他 JSON 标量的隐式转换。 */
    private fun JsonObject.text(/** 固定上源字段名。 */ key: String): String? = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    /** 整数字段使用 Long 保留字节精度并拒绝溢出。 */
    private fun JsonObject.long(/** 固定上源字段名。 */ key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
    /** 无符号人数只接受 UInt 范围整数。 */
    private fun JsonObject.uint(/** 固定上源字段名。 */ key: String): UInt? = long(key)?.takeIf { value -> value in 0L..UInt.MAX_VALUE.toLong() }?.toUInt()
}
