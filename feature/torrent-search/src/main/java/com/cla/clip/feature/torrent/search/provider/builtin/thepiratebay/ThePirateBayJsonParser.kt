package com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** ThePirateBay 显式 key JSON parser；严格顶层与逐条容错使用不同失败语义。 */
internal class ThePirateBayJsonParser(
    /** 结果与详情使用的稳定 Provider 身份。 */
    private val descriptor: ProviderDescriptor,
    /** JSON tree 与领域映射专用计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 顶层必须是数组；合法空数组和固定 sentinel 都返回空结果。 */
    suspend fun parseResults(
        /** 受网络大小上限保护的 JSON 正文。 */
        response: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** rows 解析失败或非数组时抛无正文稳定异常。 */
        val rows = parseElement(response) as? JsonArray ?: throw ProviderParseException()
        /** row 是当前外部候选，取消检查由公共遍历完成。 */
        rows.mapNotNullCancellable { row ->
            /** item 非对象时只跳过自身。 */
            val item = row as? JsonObject ?: return@mapNotNullCancellable null
            parseResultItem(item)
        }
    }

    /** 详情顶层必须是对象；身份必填缺失返回 null，可选字段独立降级。 */
    suspend fun parseDetails(
        /** 受限详情 JSON 正文。 */
        response: String,
        /** 页面 token 已验证的请求 ID。 */
        expectedTorrentId: Long,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** root 非对象属于顶层协议错误。 */
        val root = parseElement(response) as? JsonObject ?: throw ProviderParseException()
        // 完全缺少 id 键兼容固定源；存在任何 JSON 类型时都必须进入严格身份校验。
        if ("id" in root) {
            /** responseIdText 必须是字符串，null、数字、Boolean、对象和数组都拒绝。 */
            val responseIdText = root.text("id") ?: return@withContext null
            /** responseId 禁止溢出、负值或前导零身份漂移。 */
            val responseId = responseIdText.toLongOrNull()?.takeIf { value -> value > 0L }
                ?: return@withContext null
            if (responseIdText != responseId.toString() || responseId != expectedTorrentId) {
                return@withContext null
            }
        }
        /** name 是可用详情的必填展示身份。 */
        val name = root.text("name")?.takeIf(String::isNotBlank) ?: return@withContext null
        /** infoHash 统一规范化并用于公共 magnet。 */
        val infoHash = root.text("info_hash")?.let(MagnetInfoHashParser::normalize)
            ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = root.textLong("size")?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = root.textUnsigned("seeders"),
            peers = root.textUnsigned("leechers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = root.textLong("added")?.let(ProviderDateParser::epochSecondsToMillis),
            category = ThePirateBayCategoryMapper.resultCategory(root.textLong("category")),
            uploader = root.text("username"),
            magnetUri = MagnetInfoHashParser.create(infoHash),
            description = root.text("descr"),
        )
    }

    /** 映射一个列表候选；名称、规范 ID 和合法 hash 必填。 */
    private fun parseResultItem(
        /** 当前结果对象。 */
        item: JsonObject,
    ): Torrent? {
        /** name 为空或固定 sentinel 时没有结果。 */
        val name = item.text("name")?.takeIf(String::isNotBlank) ?: return null
        if (name == EMPTY_RESULTS_SENTINEL) {
            return null
        }
        /** detailsPageUrl 同时验证 ID 并构造严格本地 token。 */
        val detailsPageUrl = item.text("id")
            ?.let(ThePirateBayRequestBuilder::detailsPageUrl)
            ?: return null
        /** infoHash 统一公共规范化。 */
        val infoHash = item.text("info_hash")?.let(MagnetInfoHashParser::normalize) ?: return null
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = item.textLong("size")?.let(TorrentFileSizeFormatter::formatBytes),
            seeders = item.textUnsigned("seeders"),
            peers = item.textUnsigned("leechers"),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = item.textLong("added")?.let(ProviderDateParser::epochSecondsToMillis),
            category = ThePirateBayCategoryMapper.resultCategory(item.textLong("category")),
            descriptionPageUrl = detailsPageUrl,
        )
    }

    /** JSON 语法错误转为无正文稳定解析异常。 */
    private fun parseElement(
        /** 未信任 JSON 正文。 */
        response: String,
    ): JsonElement = runCatching {
        Json.parseToJsonElement(response)
    }.getOrNull() ?: throw ProviderParseException()

    /** 字符串字段只接受 JSON string。 */
    private fun JsonObject.text(
        /** 固定上源字段名。 */
        key: String,
    ): String? = (get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

    /** 上源数字以字符串传输；拒绝小数、溢出和其他 JSON 类型。 */
    private fun JsonObject.textLong(
        /** 固定上源字段名。 */
        key: String,
    ): Long? = text(key)?.toLongOrNull()

    /** 人数字符串只在 UInt 范围内转换。 */
    private fun JsonObject.textUnsigned(
        /** 固定上源字段名。 */
        key: String,
    ): UInt? = textLong(key)
        ?.takeIf {
            /** value 是待校验的外部人数整数。 */
            value -> value in 0L..UInt.MAX_VALUE.toLong()
        }
        ?.toUInt()

    /** 固定上游空列表哨兵常量入口。 */
    private companion object {
        /** 上游以该名称对象表达无结果。 */
        const val EMPTY_RESULTS_SENTINEL = "No results returned"
    }
}
