package com.cla.clip.feature.torrent.search.provider.builtin.subsplease

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SubsPlease 对象顶层 JSON parser；坏 show 或下载项只跳自身。 */
internal class SubsPleaseResultsParser(
    /** 结果稳定 Provider 身份。 */ private val descriptor: ProviderDescriptor,
    /** 详情 page 候选使用的同站安全策略。 */ private val urlPolicy: BuiltinProviderUrlPolicy,
    /** JSON tree 与领域映射计算调度器。 */ private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 严格解析对象顶层并按响应顺序展平合法下载项。 */
    suspend fun parse(
        /** 受 5 MiB 上限保护的 JSON。 */ responseJson: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** root 非对象或语法错误属于 Provider 协议失败。 */
        val root = runCatching { Json.parseToJsonElement(responseJson) }.getOrNull() as? JsonObject
            ?: throw ProviderParseException()
        /** entries 是 show 名到 show 对象的固定协议。 */
        root.entries.flatMap { (rawShowName, rawShow) ->
            /** showName 空白时整组无展示身份。 */
            val showName = rawShowName.trim().takeIf(String::isNotEmpty) ?: return@flatMap emptyList()
            /** showObject 非对象时只跳当前 show。 */
            val showObject = rawShow as? JsonObject ?: return@flatMap emptyList()
            parseShow(showName, showObject)
        }
    }

    /** 将一个 show 的 downloads 映射为列表结果。 */
    private suspend fun parseShow(
        /** 顶层 key 提供展示名称。 */ showName: String,
        /** 当前 show 对象。 */ showObject: JsonObject,
    ): List<Torrent> {
        /** episode 是详情精确选择的必填参数。 */
        val episode = showObject.string("episode") ?: return emptyList()
        /** downloads 必须是数组，否则当前 show 无结果。 */
        val downloads = showObject["downloads"] as? JsonArray ?: return emptyList()
        /** uploadEpochMillis 是 show 级 RFC1123 可选时间。 */
        val uploadEpochMillis = showObject.string("release_date")?.let(ProviderDateParser::parseRfc1123EpochMillis)
        /** pageCandidate 允许缺失，列表仍可保留无详情结果。 */
        val pageCandidate = showObject.string("page")
        // rawDownload 是当前分辨率下载候选，循环会优先响应父协程取消。
        return downloads.mapNotNullCancellable { rawDownload ->
            /** downloadObject 非对象时跳当前项。 */
            val downloadObject = rawDownload as? JsonObject ?: return@mapNotNullCancellable null
            /** resolution 是名称和详情匹配的必填字符串。 */
            val resolution = downloadObject.string("res") ?: return@mapNotNullCancellable null
            /** magnetUri 必须包含合法 btih。 */
            val magnetUri = downloadObject.string("magnet") ?: return@mapNotNullCancellable null
            /** infoHash 是规范化稳定身份。 */
            val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@mapNotNullCancellable null
            /** descriptionPageUrl 只有 page 合法时生成。 */
            val descriptionPageUrl = pageCandidate?.let { candidate ->
                SubsPleaseUrlBuilder.detailsUrl(candidate, episode, resolution, urlPolicy)
            }
            Torrent(
                infoHash = infoHash, name = "$showName [${resolution}p]",
                size = SubsPleaseMagnetSizeParser.parseFormattedSize(magnetUri),
                providerId = descriptor.id, providerName = descriptor.displayName,
                uploadEpochMillis = uploadEpochMillis, category = Category.ANIME,
                descriptionPageUrl = descriptionPageUrl, magnetUri = magnetUri,
            )
        }
    }

    /** 字段只接受非空 JSON 字符串。 */
    private fun JsonObject.string(
        /** 固定上源字段名。 */ key: String,
    ): String? = (get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

/** SubsPlease magnet `xl` 字节数解析，仅服务该站点专属协议。 */
internal object SubsPleaseMagnetSizeParser {
    /** 解析首个合法非负 xl 并复用公共 IEC formatter。 */
    fun parseFormattedSize(
        /** 已通过 btih 校验的 magnet URI。 */ magnetUri: String,
    ): String? {
        /** parsedUri 只在内存读取 opaque 查询。 */
        val parsedUri = runCatching { URI(magnetUri) }.getOrNull() ?: return null
        /** rawQuery 来自 `?` 后 scheme-specific part。 */
        val rawQuery = parsedUri.rawSchemeSpecificPart?.takeIf { part -> part.startsWith('?') }
            ?.removePrefix("?")
            ?: return null
        /** rawValue 只接受名字精确为 xl 的首个参数。 */
        val rawValue = rawQuery.split('&').firstNotNullOfOrNull { parameter ->
            /** pair 拆分当前参数名和值。 */
            val pair = parameter.split('=', limit = 2)
            pair.takeIf { it.size == 2 && it[0] == "xl" }?.get(1)
        } ?: return null
        /** bytes 解码后必须是 Long 范围内非负整数。 */
        /** decodedBytes 是解码后仍需验证范围的字节数字符串。 */
        val decodedBytes = runCatching { URLDecoder.decode(rawValue, Charsets.UTF_8.name()) }.getOrNull()
        /** bytes 必须是 Long 范围内非负整数。 */
        val bytes = decodedBytes?.toLongOrNull() ?: return null
        if (bytes < 0L) return null
        return TorrentFileSizeFormatter.formatBytes(bytes)
    }
}
