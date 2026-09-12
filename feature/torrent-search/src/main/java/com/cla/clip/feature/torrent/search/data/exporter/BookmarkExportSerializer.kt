package com.cla.clip.feature.torrent.search.data.exporter

import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportParser
import com.cla.clip.feature.torrent.search.data.model.BookmarkContractValidator
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 书签导出整体失败的固定分类，不包含文件、URI 或业务内容。 */
internal enum class BookmarkExportFailureReason {
    /** 条目超过 v1 协议与导入器共同允许的 10,000 条。 */
    TOO_MANY_ITEMS,
    /** 最终 UTF-8 文档超过 10 MiB，必须整体失败而不能截断。 */
    TOO_LARGE,
    /** 单项不满足保存与 05C 解析器共享协议，禁止生成不可恢复文档。 */
    INVALID_ITEM,
}

/** 书签导出结果；成功字节可直接交给一次性 SAF 输出流。 */
internal sealed interface BookmarkExportResult {
    /** 完整且未截断的 v1 JSON 文档。 */
    data class Success(
        /** 固定 UTF-8 编码的完整文档字节。 */
        val bytes: ByteArray,
    ) : BookmarkExportResult

    /** 导出被稳定边界拒绝。 */
    data class Failure(
        /** 不含业务数据的固定失败分类。 */
        val reason: BookmarkExportFailureReason,
    ) : BookmarkExportResult
}

/** 生成与 05C 解析器完全兼容的稳定 v1 JSON 包络。 */
internal class BookmarkExportSerializer @Inject constructor() {
    /** 编码固定离开 Main，测试构造可替换以验证调度与取消。 */
    private var encodingDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** 测试与独立调用方可替换编码 dispatcher。 */
    internal constructor(
        /** 受调用方控制的后台编码 dispatcher。 */
        encodingDispatcher: CoroutineDispatcher,
    ) : this() {
        this.encodingDispatcher = encodingDispatcher
    }
    /** 逐项导出全部书签；超过条数、单项或字节上限时不返回部分内容。 */
    suspend fun serialize(
        /** 已取得的同一书签快照，调用期间不会再次读取 Room。 */
        bookmarks: List<BookmarkedTorrent>,
    ): BookmarkExportResult = withContext(encodingDispatcher) {
        if (bookmarks.size > BookmarkImportParser.MAX_IMPORT_ITEMS) {
            return@withContext BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_MANY_ITEMS)
        }
        /** output 只保留不超过 parser 上限的最终字节，拒绝先构造巨型 DOM 或字符串。 */
        val output = ByteArrayOutputStream(minOf(64 * 1024, BookmarkImportParser.MAX_IMPORT_BYTES))
        if (!output.appendBounded(ENVELOPE_PREFIX)) {
            return@withContext BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_LARGE)
        }
        bookmarks.forEachIndexed { /** index 是当前快照中的稳定输出序号。 */ index, /** bookmark 是待校验与编码的领域书签。 */ bookmark ->
            coroutineContext.ensureActive()
            /** canonicalBookmark 由保存、解析和导出共用校验器生成。 */
            val canonicalBookmark = BookmarkContractValidator.normalize(bookmark)
                ?: return@withContext BookmarkExportResult.Failure(BookmarkExportFailureReason.INVALID_ITEM)
            /** itemBytes 只包含单条有界 JSON，不构建整包对象树。 */
            val itemBytes = Json.encodeToString(JsonObject.serializer(), canonicalBookmark.toJsonObject()).toByteArray(Charsets.UTF_8)
            /** separator 在第二项开始前写入固定逗号。 */
            val separator = if (index == 0) EMPTY_BYTES else COMMA_BYTES
            if (!output.appendBounded(separator) || !output.appendBounded(itemBytes)) {
                return@withContext BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_LARGE)
            }
        }
        coroutineContext.ensureActive()
        if (!output.appendBounded(ENVELOPE_SUFFIX)) {
            return@withContext BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_LARGE)
        }
        return@withContext BookmarkExportResult.Success(output.toByteArray())
    }

    /** 在写入前检查最终容量，失败时不追加任何当前分块。 */
    private fun ByteArrayOutputStream.appendBounded(
        /** 当前待追加 UTF-8 分块。 */
        bytes: ByteArray,
    ): Boolean {
        if (size() > BookmarkImportParser.MAX_IMPORT_BYTES - bytes.size) return false
        write(bytes)
        return true
    }

    /** 将单个领域书签映射到 v1 固定字段顺序，null 仍显式保留以稳定协议。 */
    private fun BookmarkedTorrent.toJsonObject(): JsonObject {
        /** fields 使用显式插入顺序，避免反射、属性名或类名成为外部协议。 */
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        fields["infoHash"] = JsonPrimitive(infoHash)
        fields["name"] = JsonPrimitive(name)
        fields["providerId"] = JsonPrimitive(providerId.value)
        fields["providerName"] = JsonPrimitive(providerName)
        fields["size"] = size.toJsonPrimitiveOrNull()
        fields["seeders"] = seeders?.let { /** value 是 UInt 范围内的做种数快照。 */ value -> JsonPrimitive(value.toLong()) } ?: JsonNull
        fields["peers"] = peers?.let { /** value 是 UInt 范围内的连接数快照。 */ value -> JsonPrimitive(value.toLong()) } ?: JsonNull
        fields["uploadEpochMillis"] = uploadEpochMillis?.let(::JsonPrimitive) ?: JsonNull
        fields["category"] = category?.code.toJsonPrimitiveOrNull()
        fields["descriptionPageUrl"] = descriptionPageUrl.toJsonPrimitiveOrNull()
        fields["magnetUri"] = magnetUri.toJsonPrimitiveOrNull()
        fields["fileDownloadLink"] = fileDownloadLink.toJsonPrimitiveOrNull()
        fields["bookmarkedAtEpochMillis"] = JsonPrimitive(bookmarkedAtEpochMillis)
        return JsonObject(fields)
    }

    /** 把可空字符串显式编码为字符串或 JSON null。 */
    private fun String?.toJsonPrimitiveOrNull(): kotlinx.serialization.json.JsonElement {
        return this?.let(::JsonPrimitive) ?: JsonNull
    }

    /** 紧凑 JSON 固定包络字节。 */
    companion object {
        /** v1 包络前缀。 */
        private val ENVELOPE_PREFIX = "{\"version\":1,\"bookmarks\":[".toByteArray(Charsets.UTF_8)
        /** v1 包络后缀。 */
        private val ENVELOPE_SUFFIX = "]}".toByteArray(Charsets.UTF_8)
        /** 非首项分隔符。 */
        private val COMMA_BYTES = byteArrayOf(','.code.toByte())
        /** 首项不需要分隔符。 */
        private val EMPTY_BYTES = byteArrayOf()
    }
}
