package com.cla.clip.feature.torrent.search.data.exporter

import com.cla.clip.feature.torrent.search.data.importer.BookmarkImportParser
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 书签导出整体失败的固定分类，不包含文件、URI 或业务内容。 */
internal enum class BookmarkExportFailureReason {
    /** 条目超过 v1 协议与导入器共同允许的 10,000 条。 */
    TOO_MANY_ITEMS,
    /** 最终 UTF-8 文档超过 10 MiB，必须整体失败而不能截断。 */
    TOO_LARGE,
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
internal object BookmarkExportSerializer {
    /** 导出全部书签；超过条数或最终字节上限时不返回部分内容。 */
    fun serialize(
        /** 已取得的同一书签快照，调用期间不会再次读取 Room。 */
        bookmarks: List<BookmarkedTorrent>,
    ): BookmarkExportResult {
        if (bookmarks.size > BookmarkImportParser.MAX_IMPORT_ITEMS) {
            return BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_MANY_ITEMS)
        }
        /** bookmarkElements 按当前仓库倒序快照顺序固定输出。 */
        val bookmarkElements = bookmarks.map { /** bookmark 是待转换为稳定字段对象的领域书签。 */ bookmark ->
            bookmark.toJsonObject()
        }
        /** envelope 固定先写 version 再写 bookmarks，便于审查和稳定 fixture。 */
        val envelope = JsonObject(
            linkedMapOf(
                "version" to JsonPrimitive(1),
                "bookmarks" to JsonArray(bookmarkElements),
            ),
        )
        /** bytes 是紧凑 JSON 的完整 UTF-8 表示，大小判断发生在任何写出之前。 */
        val bytes = Json.encodeToString(JsonObject.serializer(), envelope).toByteArray(Charsets.UTF_8)
        if (bytes.size > BookmarkImportParser.MAX_IMPORT_BYTES) {
            return BookmarkExportResult.Failure(BookmarkExportFailureReason.TOO_LARGE)
        }
        return BookmarkExportResult.Success(bytes)
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
}
