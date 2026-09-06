package com.cla.clip.feature.torrent.search.provider.builtin.btsow

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Btsow JSON 纯解析边界；坏顶层抛低敏异常，坏条目跳过，缺必填详情返回 null。 */
internal class BtsowJsonParser(
    /** 结果中的稳定来源字段。 */
    private val descriptor: ProviderDescriptor,
    /** 所有响应 JSON tree 和领域映射的执行调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 搜索 data 必须为数组；协作遍历保持顺序并及时响应取消。 */
    suspend fun parseResults(
        /** 有上限的原始响应，只在内存解析。 */
        response: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** 顶层协议数组，空数组是合法零结果。 */
        val rows = parseObject(response)["data"] as? JsonArray ?: throw ProviderParseException()
        /** row 是单条不可信 JSON，非对象条目只跳过。 */
        rows.mapNotNullCancellable { row ->
            /** 当前候选对象，不影响后续合法条目。 */
            val item = row as? JsonObject ?: return@mapNotNullCancellable null
            /** 规范化身份和去固定强调标记后的名称都是必填。 */
            val infoHash = item.text("hash")?.let(MagnetInfoHashParser::normalize)
                ?: return@mapNotNullCancellable null
            /** 仅移除上游强调标签，不执行 HTML 或宽泛替换用户内容。 */
            val name = item.text("name")?.replace("<em>", "")?.replace("</em>", "")
                ?.takeIf(String::isNotBlank) ?: return@mapNotNullCancellable null
            /** 详情路径使用规范 hash 单个片段。 */
            val detailsUrl = descriptor.baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("magnet").addPathSegment("detail").addPathSegment(infoHash).build().toString()
            Torrent(
                infoHash = infoHash, name = name,
                size = item.number("size")?.let(TorrentFileSizeFormatter::formatBytes),
                providerId = descriptor.id, providerName = descriptor.displayName,
                descriptionPageUrl = detailsUrl,
            )
        }
    }

    /** 详情必须有对象 data 且身份匹配；可选时间溢出只降级为空。 */
    suspend fun parseDetails(
        /** 原始响应不得进入异常消息。 */
        response: String,
        /** 请求端已规范化的稳定身份。 */
        expectedHash: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** data 类型错误是协议失败，与缺单条字段区分。 */
        val item = parseObject(response)["data"] as? JsonObject ?: throw ProviderParseException()
        /** 响应身份必须与请求一致，避免保存错误资源。 */
        val infoHash = item.text("hash")?.let(MagnetInfoHashParser::normalize) ?: return@withContext null
        if (infoHash != expectedHash) return@withContext null
        /** 名称为空时没有可展示详情。 */
        val name = item.text("name")?.takeIf(String::isNotBlank) ?: return@withContext null
        TorrentDetails(
            infoHash = infoHash, name = name,
            providerId = descriptor.id, providerName = descriptor.displayName,
            magnetUri = MagnetInfoHashParser.create(infoHash),
            size = item.number("size")?.let(TorrentFileSizeFormatter::formatBytes),
            uploadEpochMillis = item.number("date")?.let(ProviderDateParser::epochSecondsToMillis),
            lastCheckedEpochMillis = item.number("lastUpdateTime")?.let(ProviderDateParser::epochSecondsToMillis),
        )
    }

    /** JSON 语法错误与非对象顶层均转为不含正文的稳定异常。 */
    private fun parseObject(response: String): JsonObject =
        (runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject)
            ?: throw ProviderParseException()

    /** 只接受 JSON 字符串，禁止数字或 null 被强制转为名称/hash。 */
    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    /** 数值只接受可表示的 Long，缺失、对象和溢出都降级为空。 */
    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
}
