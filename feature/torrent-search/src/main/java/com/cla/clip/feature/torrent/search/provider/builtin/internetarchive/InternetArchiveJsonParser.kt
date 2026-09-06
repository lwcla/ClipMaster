package com.cla.clip.feature.torrent.search.provider.builtin.internetarchive

import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.TorrentFileSizeFormatter
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** IA 显式 key JSON 解析，不使用反射模型；坏顶层失败、坏条目跳过、必填详情缺失为空。 */
internal class InternetArchiveJsonParser(
    /** 来源身份由 Provider 显式注入。 */
    private val descriptor: ProviderDescriptor,
    /** 所有 JSON 解码与领域映射的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** response.docs 必须为数组，合法空数组保留零结果语义。 */
    suspend fun parseResults(
        /** 有大小上限的原始 JSON，不进入异常消息。 */
        response: String,
    ): List<Torrent> = withContext(parsingDispatcher) {
        /** search response 容器不是对象时视为协议结构错误。 */
        val container = parseObject(response)["response"] as? JsonObject ?: throw ProviderParseException()
        /** docs 为必填数组，非对象单条在下面单独跳过。 */
        val rows = container["docs"] as? JsonArray ?: throw ProviderParseException()
        /** row 是单个外部候选，协作取消不被坏字段容错吞掉。 */
        rows.mapNotNullCancellable { row ->
            /** 非对象元素不得影响后续合法候选。 */
            val item = row as? JsonObject ?: return@mapNotNullCancellable null
            /** 标题必须是非空 JSON 字符串。 */
            val name = item.text("title")?.takeIf(String::isNotBlank) ?: return@mapNotNullCancellable null
            /** 字节数全程 Long 输入，避免上游 Float 精度损失。 */
            val size = item.number("item_size")?.let(TorrentFileSizeFormatter::formatBytes) ?: return@mapNotNullCancellable null
            /** 分类字段存在是上源必填契约，未知字符串映射 OTHER。 */
            val category = item.text("mediatype")?.let(InternetArchiveCategoryMapper::category) ?: return@mapNotNullCancellable null
            /** 标识必须成为安全单路径段，不能产出另一个 API 路径。 */
            val url = item.text("identifier")?.let(InternetArchiveUrlBuilder::detailsUrl) ?: return@mapNotNullCancellable null
            /** hash 统一公共 hex/base32 稳定键。 */
            val hash = item.text("btih")?.let(MagnetInfoHashParser::normalize) ?: return@mapNotNullCancellable null
            Torrent(
                infoHash = hash, name = name, size = size, category = category,
                providerId = descriptor.id, providerName = descriptor.displayName, descriptionPageUrl = url,
                uploadEpochMillis = item.text("publicdate")?.let(ProviderDateParser::parseIsoEpochMillis),
            )
        }
    }

    /** 详情需 metadata 的名称/标识与有效 torrent 文件；可选 epoch 溢出降级为空。 */
    suspend fun parseDetails(
        /** 受限 JSON 正文，不向错误或日志回显。 */
        response: String,
        /** 网络前已验证的请求标识，必须与响应一致。 */
        expectedIdentifier: String,
    ): TorrentDetails? = withContext(parsingDispatcher) {
        /** 顶层非对象仍属结构异常。 */
        val root = parseObject(response)
        /** 缺 metadata 即没有可用详情，字段本身类型异常同样不可用。 */
        val metadata = root["metadata"] as? JsonObject ?: return@withContext null
        /** 名称是详情展示必填字段。 */
        val name = metadata.text("title")?.takeIf(String::isNotBlank) ?: return@withContext null
        /** 响应标识精确匹配请求，避免把其他资源误当当前结果。 */
        val identifier = metadata.text("identifier") ?: return@withContext null
        if (identifier != expectedIdentifier || !InternetArchiveUrlBuilder.validIdentifier(identifier)) return@withContext null
        /** 缺少文件数组即无合法 torrent 详情。 */
        val files = root["files"] as? JsonArray ?: return@withContext null
        /** file 是原始文件对象；非法首文件必须允许继续尝试后续条目。 */
        for (file in files) {
            currentCoroutineContext().ensureActive()
            /** 非对象文件直接跳过。 */
            val item = file as? JsonObject ?: continue
            /** 当前文件 btih 必须有效，存在键但值非法不能提前结束。 */
            val hash = item.text("btih")?.let(MagnetInfoHashParser::normalize) ?: continue
            /** 文件名与标识结构化编码，空/点段不能进入下载地址。 */
            val downloadUrl = item.text("name")?.let { InternetArchiveUrlBuilder.downloadUrl(identifier, it) } ?: continue
            return@withContext TorrentDetails(
                infoHash = hash, name = name, providerId = descriptor.id, providerName = descriptor.displayName,
                magnetUri = MagnetInfoHashParser.create(hash), fileDownloadLink = downloadUrl,
                size = root.number("item_size")?.let(TorrentFileSizeFormatter::formatBytes),
                lastCheckedEpochMillis = root.number("item_last_updated")?.let(ProviderDateParser::epochSecondsToMillis),
                uploadEpochMillis = metadata.text("publicdate")?.let { ProviderDateParser.parseUtcEpochMillis(it, "yyyy-MM-dd HH:mm:ss") },
                // 修复固定上源误读 metadata.metadata；真实协议分类字段是 mediatype。
                category = metadata.text("mediatype")?.let(InternetArchiveCategoryMapper::category),
                uploader = metadata.text("uploader"), description = metadata.text("description"),
            )
        }
        null
    }

    /** 仅将 JSON 语法与非对象顶层转换为无正文稳定异常。 */
    private fun parseObject(
        /** 原始 JSON 文本，不进入日志或异常消息。 */
        response: String,
    ): JsonObject =
        (runCatching { Json.parseToJsonElement(response) }.getOrNull() as? JsonObject) ?: throw ProviderParseException()

    /** 字符串协议字段不接受 JSON 数字、Boolean 或 null 的隐式转换。 */
    private fun JsonObject.text(
        /** 固定上源 JSON 字段名，不来自用户输入。 */
        key: String,
    ): String? = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    /** 仅接受可表示的整数，对象、小数和溢出返回 null。 */
    private fun JsonObject.number(
        /** 固定上源 JSON 字段名，不来自用户输入。 */
        key: String,
    ): Long? = (get(key) as? JsonPrimitive)?.longOrNull
}
