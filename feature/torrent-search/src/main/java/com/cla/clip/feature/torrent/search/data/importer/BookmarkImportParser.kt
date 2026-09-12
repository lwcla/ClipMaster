package com.cla.clip.feature.torrent.search.data.importer

import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.security.BoundedInputReadResult
import com.cla.clip.feature.torrent.search.security.BoundedInputReader
import com.cla.clip.feature.torrent.search.security.ExternalLinkPolicy
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 书签中单个外部链接最大字符数，顶层扩展与解析器共享同一安全边界。 */
private const val MAX_BOOKMARK_LINK_CHARS = 8192

/** 书签导入整包失败的稳定协议；单条字段错误只进入 skippedCount。 */
internal enum class BookmarkImportFailureReason(
    /** 对外稳定 reasonCode，不附带 JSON、URI 或字段内容。 */
    val code: String,
) {
    /** 输入为空。 */
    EMPTY("empty"),
    /** 输入超过 10 MiB。 */
    TOO_LARGE("too_large"),
    /** 输入流发生低敏 IO 失败。 */
    READ_FAILURE("read_failure"),
    /** UTF-8 或 JSON 语法损坏。 */
    MALFORMED_JSON("malformed_json"),
    /** 顶层不是数组或版本 envelope。 */
    INVALID_ROOT("invalid_root"),
    /** envelope 版本不是固定版本 1。 */
    UNSUPPORTED_VERSION("unsupported_version"),
    /** JSON 嵌套超过固定安全深度。 */
    JSON_TOO_DEEP("json_too_deep"),
    /** 书签数组直接子项超过固定数量。 */
    TOO_MANY_ITEMS("too_many_items"),
    /** envelope 字段名超过固定小上限。 */
    ENVELOPE_KEY_TOO_LONG("envelope_key_too_long"),
}

/** 书签导入解析结果；成功允许合法空列表并提供低敏计数。 */
internal sealed interface BookmarkImportResult {
    /** 整包结构可解析，单条错误已隔离。 */
    data class Success(
        /** 按输入顺序保留且已按 infoHash 去重的合法书签。 */
        val bookmarks: List<BookmarkedTorrent>,
        /** 因结构、字段或身份不安全而跳过的条目数。 */
        val skippedCount: Int,
        /** 与此前合法 infoHash 重复而跳过的条目数。 */
        val duplicateCount: Int,
    ) : BookmarkImportResult

    /** 整包无法安全解析。 */
    data class Failure(
        /** 稳定失败分类。 */
        val reason: BookmarkImportFailureReason,
    ) : BookmarkImportResult
}

/** 解析本仓库 v1 与固定上游数组格式，不直接产生数据库副作用。 */
internal object BookmarkImportParser {
    /** 书签导入文件固定上限 10 MiB。 */
    const val MAX_IMPORT_BYTES = 10 * 1024 * 1024
    /** JSON 最大深度，防止小文件构造递归或 DOM 放大。 */
    const val MAX_JSON_DEPTH = 64
    /** 单次导入最大直接条目数，避免海量微小对象耗尽内存。 */
    const val MAX_IMPORT_ITEMS = 10_000
    /** 单条原始 JSON 最大 256 KiB，超出时在创建 substring/DOM 前逐项跳过。 */
    const val MAX_BOOKMARK_ITEM_CHARS = 256 * 1024
    /** envelope 字段名最大 64 字符，未知巨型 key 不得产生大字符串副本。 */
    const val MAX_ENVELOPE_KEY_CHARS = 64
    /** 展示名称上限与搜索输入边界一致，避免单条内容膨胀 UI。 */
    private const val MAX_NAME_CHARS = 512
    /** Provider 展示名只用于 UI 快照，限制为 128 字符。 */
    private const val MAX_PROVIDER_NAME_CHARS = 128
    /** 大小展示文本限制为 128 字符，禁止借可选字段承载大段内容。 */
    private const val MAX_SIZE_TEXT_CHARS = 128
    /** 外部链接单字段最大 8192 字符，超出时整项跳过。 */
    private const val MAX_LINK_CHARS = MAX_BOOKMARK_LINK_CHARS
    /** 固定上游导出没有稳定 Provider ID，统一使用此兼容身份。 */
    private val LEGACY_PROVIDER_ID = ProviderId("legacy_import")
    /** JSON DOM 仅负责语法树，字段类型和兼容语义由本解析器逐项校验。 */
    private val JSON = Json

    /** 从调用方拥有的流解析书签；本方法不关闭输入流。 */
    fun parse(
        /** SAF 或测试提供的不可信 JSON 流。 */
        input: InputStream,
    ): BookmarkImportResult {
        /** readResult 在 JSON 解码前执行真实 10 MiB 上限。 */
        val readResult = BoundedInputReader.read(input, MAX_IMPORT_BYTES)
        /** bytes 只在完整读取成功时存在。 */
        val bytes = when (readResult) {
            is BoundedInputReadResult.Success -> readResult.bytes
            BoundedInputReadResult.TooLarge -> return BookmarkImportResult.Failure(BookmarkImportFailureReason.TOO_LARGE)
            BoundedInputReadResult.ReadFailure -> return BookmarkImportResult.Failure(BookmarkImportFailureReason.READ_FAILURE)
        }
        if (bytes.isEmpty()) return BookmarkImportResult.Failure(BookmarkImportFailureReason.EMPTY)
        /** text 使用严格 UTF-8 解码，非法序列不得被替换字符静默接受。 */
        val text = decodeStrictUtf8(bytes)
            ?: return BookmarkImportResult.Failure(BookmarkImportFailureReason.MALFORMED_JSON)
        /** scanResult 单遍验证完整 JSON，并只记录数组直接子项下标。 */
        val scanResult = BookmarkJsonEnvelopeScanner.scan(
            text = text,
            maxDepth = MAX_JSON_DEPTH,
            maxItems = MAX_IMPORT_ITEMS,
            maxEnvelopeKeyChars = MAX_ENVELOPE_KEY_CHARS,
        )
        if (scanResult is BookmarkJsonEnvelopeScanResult.Failure) {
            return BookmarkImportResult.Failure(scanResult.reason)
        }
        /** envelope 是已验证的单项区间与格式标志。 */
        val envelope = scanResult as BookmarkJsonEnvelopeScanResult.Success
        /** bookmarks 按首次出现顺序保存合法稳定身份。 */
        val bookmarks = linkedMapOf<String, BookmarkedTorrent>()
        /** skippedCount 累计坏对象或坏字段条目。 */
        var skippedCount = 0
        /** duplicateCount 累计文件内重复稳定身份。 */
        var duplicateCount = 0
        /** itemRange 是当前条目在完整文本中的半开区间。 */
        for (itemRange in envelope.itemRanges) {
            if (itemRange.endExclusive - itemRange.start > MAX_BOOKMARK_ITEM_CHARS) {
                skippedCount += 1
                continue
            }
            /** element 仅为当前条目创建短生命周期 DOM，避免 10 MiB 整包对象树。 */
            val element = runCatching {
                JSON.parseToJsonElement(text.substring(itemRange.start, itemRange.endExclusive))
            }.getOrNull()
            /** bookmark 失败或意外单项解析失败只影响当前元素。 */
            val bookmark = element?.let { /** parsedElement 是已独立解析的当前 JSON 值。 */ parsedElement ->
                parseBookmark(parsedElement, envelope.isLegacyArray)
            }
            if (bookmark == null) {
                skippedCount += 1
            } else if (bookmarks.putIfAbsent(bookmark.infoHash, bookmark) != null) {
                duplicateCount += 1
            }
        }
        return BookmarkImportResult.Success(
            bookmarks = bookmarks.values.toList(),
            skippedCount = skippedCount,
            duplicateCount = duplicateCount,
        )
    }

    /** 解析单项并执行字段、身份、链接和兼容别名校验。 */
    private fun parseBookmark(
        /** 当前数组元素。 */
        element: JsonElement,
        /** true 表示固定上游无版本数组。 */
        isLegacyArray: Boolean,
    ): BookmarkedTorrent? {
        /** item 必须是 JSON 对象。 */
        val item = element as? JsonObject ?: return null
        /** name 是必需展示文本，删除控制字符后仍需非空且不超限。 */
        val name = item.requiredString("name")?.sanitizeText()?.takeIf { value ->
            value.isNotEmpty() && value.length <= MAX_NAME_CHARS
        } ?: return null
        /** rawMagnet 缺失或 null 才可为空，错误 JSON 类型直接跳过当前项。 */
        val rawMagnet = item.optionalString("magnetUri")
        if (rawMagnet == null && item.hasNonNull("magnetUri")) return null
        /** magnetUri 是可选身份来源，存在时必须完整合法。 */
        val magnetUri = rawMagnet?.let { /** magnetText 是导入项中的原始磁力链接。 */ magnetText ->
            magnetText.trim().takeIf { /** value 是已裁剪但尚未验证的磁力链接。 */ value ->
                value.length <= MAX_LINK_CHARS &&
                    ExternalLinkPolicy.isAllowed(value) &&
                    runCatching { java.net.URI(value).scheme?.equals("magnet", ignoreCase = true) == true }.getOrDefault(false)
            } ?: return null
        }
        /** rawInfoHash 缺失或 null 才可由 magnet 恢复，错误类型不能降级。 */
        val rawInfoHash = item.optionalString("infoHash")
        if (rawInfoHash == null && item.hasNonNull("infoHash")) return null
        /** explicitHash 是可选显式身份，存在时必须为规范 BitTorrent v1 hash。 */
        val explicitHash = rawInfoHash?.let { rawHash ->
            MagnetInfoHashParser.normalize(rawHash) ?: return null
        }
        /** magnetHash 用于恢复缺失身份并验证显式值一致。 */
        val magnetHash = magnetUri?.let(MagnetInfoHashParser::parse)
        if (magnetUri != null && magnetHash == null) return null
        if (explicitHash != null && magnetHash != null && explicitHash != magnetHash) return null
        /** infoHash 优先使用显式稳定键，缺失时从合法 magnet 恢复。 */
        val infoHash = explicitHash ?: magnetHash ?: return null
        /** providerName 是固定上游和新协议都必需的展示快照。 */
        val providerName = item.requiredString("providerName")?.sanitizeText()?.takeIf { value ->
            value.isNotEmpty() && value.length <= MAX_PROVIDER_NAME_CHARS
        } ?: return null
        /** providerId 新协议必须显式提供；旧数组统一映射兼容身份。 */
        val providerId = if (isLegacyArray) {
            LEGACY_PROVIDER_ID
        } else {
            item.requiredString("providerId")?.let { /** rawId 是新协议显式提供的来源身份。 */ rawId ->
                runCatching { ProviderId(rawId) }.getOrNull()
            } ?: return null
        }
        /** size 是可选短展示文本，错误类型或超长值使当前项失败。 */
        val size = item.validatedOptionalString("size", MAX_SIZE_TEXT_CHARS) ?: if (item.containsKey("size") && item["size"] !is kotlinx.serialization.json.JsonNull) return null else null
        /** seeders 是 UInt 范围内的可选整数。 */
        val seeders = item.optionalUInt("seeders") ?: if (item.hasNonNull("seeders")) return null else null
        /** peers 是 UInt 范围内的可选整数。 */
        val peers = item.optionalUInt("peers") ?: if (item.hasNonNull("peers")) return null else null
        /** uploadEpochMillis 兼容上游 uploadDate 别名，并拒绝负时间。 */
        val uploadEpochMillis = item.optionalNonNegativeLong(if (isLegacyArray) "uploadDate" else "uploadEpochMillis")
            ?: if (item.hasNonNull(if (isLegacyArray) "uploadDate" else "uploadEpochMillis")) return null else null
        /** category 同时兼容稳定 code 与固定上游枚举展示名。 */
        val category = parseCategory(item.optionalString("category"), isLegacyArray)
            ?: if (item.hasNonNull("category")) return null else null
        /** descriptionPageUrl 只允许 HTTP/HTTPS。 */
        val descriptionPageUrl = item.optionalHttpUrl("descriptionPageUrl") ?: if (item.hasNonNull("descriptionPageUrl")) return null else null
        /** fileDownloadLink 只允许 HTTP/HTTPS。 */
        val fileDownloadLink = item.optionalHttpUrl("fileDownloadLink") ?: if (item.hasNonNull("fileDownloadLink")) return null else null
        /** bookmarkedAtEpochMillis 只属于新协议；旧 id 不参与宿主稳定身份。 */
        val bookmarkedAtEpochMillis = if (isLegacyArray) {
            0L
        } else {
            item.optionalNonNegativeLong("bookmarkedAtEpochMillis")
                ?: if (item.hasNonNull("bookmarkedAtEpochMillis")) return null else 0L
        }
        return BookmarkedTorrent(
            infoHash = infoHash,
            name = name,
            providerId = providerId,
            providerName = providerName,
            size = size,
            seeders = seeders,
            peers = peers,
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            descriptionPageUrl = descriptionPageUrl,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
            bookmarkedAtEpochMillis = bookmarkedAtEpochMillis,
        )
    }

    /** 使用 REPORT 策略严格解码 UTF-8，避免损坏字节被替换后进入字段。 */
    private fun decodeStrictUtf8(
        /** 已通过大小上限的输入字节。 */
        bytes: ByteArray,
    ): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /** 将可选分类映射为稳定枚举；未知值不回退 All。 */
    private fun parseCategory(
        /** JSON 中的分类文本。 */
        rawCategory: String?,
        /** true 时使用固定上游枚举名兼容。 */
        isLegacyArray: Boolean,
    ): Category? {
        if (rawCategory == null) return null
        return if (isLegacyArray) {
            Category.entries.firstOrNull { /** category 是当前待匹配的上游枚举项。 */ category ->
                category.name.equals(rawCategory, ignoreCase = true)
            }
        } else {
            Category.entries.firstOrNull { /** category 是当前待匹配的稳定分类项。 */ category ->
                category.code == rawCategory
            }
        }
    }
}

/** 读取必需 JSON 字符串；数字、布尔、null、对象和数组均拒绝。 */
private fun JsonObject.requiredString(
    /** JSON 字段名。 */
    key: String,
): String? {
    /** primitive 必须明确是字符串，不能把数字或布尔转成文本。 */
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}

/** 读取可选字符串；缺失或 JSON null 返回 null，错误类型也由调用方存在性检查拒绝。 */
private fun JsonObject.optionalString(
    /** JSON 字段名。 */
    key: String,
): String? = requiredString(key)

/** 读取有长度上限的可选字符串。 */
private fun JsonObject.validatedOptionalString(
    /** JSON 字段名。 */
    key: String,
    /** 允许的最大字符数。 */
    maxChars: Int,
): String? = optionalString(key)?.takeIf { /** value 是待执行长度校验的可选文本。 */ value -> value.length <= maxChars }

/** 读取 UInt 范围可选整数。 */
private fun JsonObject.optionalUInt(
    /** JSON 字段名。 */
    key: String,
): UInt? {
    /** primitive 必须是 JSON 数字，字符串形式不作为兼容输入。 */
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    /** value 是已通过类型检查的整数值。 */
    val value = primitive.longOrNull ?: return null
    return value.takeIf { /** number 是待执行 UInt 边界校验的整数。 */ number ->
        number in 0L..UInt.MAX_VALUE.toLong()
    }?.toUInt()
}

/** 读取非负 Long 时间。 */
private fun JsonObject.optionalNonNegativeLong(
    /** JSON 字段名。 */
    key: String,
): Long? {
    /** primitive 必须是 JSON 数字，字符串形式不作为兼容输入。 */
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    /** value 是已通过类型检查的长整数值。 */
    val value = primitive.longOrNull ?: return null
    return value.takeIf { /** number 是待执行非负边界校验的时间值。 */ number -> number >= 0L }
}

/** 读取并验证 HTTP/HTTPS 可选链接。 */
private fun JsonObject.optionalHttpUrl(
    /** JSON 字段名。 */
    key: String,
): String? {
    /** value 去除边界空白后执行统一外部 scheme 和长度校验。 */
    val value = optionalString(key)?.trim() ?: return null
    if (value.length > MAX_BOOKMARK_LINK_CHARS || !ExternalLinkPolicy.isAllowed(value)) return null
    /** parsedUrl 复用 OkHttp authority 解析，拒绝缺少 host 或携带 userinfo 的伪链接。 */
    val parsedUrl = value.toHttpUrlOrNull() ?: return null
    if (parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) return null
    return parsedUrl.toString()
}

/** 判断字段是否存在且不是 JSON null，用于区分缺失与坏类型。 */
private fun JsonObject.hasNonNull(
    /** JSON 字段名。 */
    key: String,
): Boolean = containsKey(key) && this[key] !is kotlinx.serialization.json.JsonNull

/** 删除 Unicode 控制字符并裁剪边界空白，避免导入不可见文本。 */
private fun String.sanitizeText(): String {
    /** sanitized 只保留非控制字符，规则与查询入口保持一致。 */
    val sanitized = buildString(length) {
        /** character 是当前候选 Unicode 字符。 */
        for (character in this@sanitizeText) {
            if (!character.isISOControl()) append(character)
        }
    }
    return sanitized.trim()
}
