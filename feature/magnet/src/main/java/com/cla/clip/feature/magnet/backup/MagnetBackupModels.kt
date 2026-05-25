package com.cla.clip.feature.magnet.backup

import androidx.annotation.Keep
import com.cla.clip.feature.magnet.MagnetInfoHashNormalizer
import com.cla.clip.feature.magnet.MagnetSourceProvider
import com.cla.clip.feature.magnet.MagnetTextNormalizer
import com.cla.clip.feature.magnet.MagnetUriBuilder
import com.cla.clip.feature.magnet.data.MagnetDownloadRecordData
import com.cla.clip.feature.magnet.data.MagnetSearchHistoryData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val MAGNET_SEARCH_HISTORIES_PATH = "data/magnet_search_histories.json"
internal const val MAGNET_SEARCH_HISTORIES_JSONL_PATH = "data/magnet_search_histories.jsonl"
internal const val MAGNET_DOWNLOAD_RECORDS_PATH = "data/magnet_download_records.json"
internal const val MAGNET_DOWNLOAD_RECORDS_JSONL_PATH = "data/magnet_download_records.jsonl"

internal const val MAGNET_SEARCH_HISTORY_COUNT_KEY = "magnet_search_history_count"
internal const val MAGNET_DOWNLOAD_RECORD_COUNT_KEY = "magnet_download_record_count"
internal const val MAGNET_SEARCH_HISTORIES_CATEGORY = "magnet_search_histories"
internal const val MAGNET_DOWNLOAD_RECORDS_CATEGORY = "magnet_download_records"

/** 磁力搜索历史备份字段，以规范化关键词唯一索引恢复。 */
@Keep
@Serializable
internal data class BackupMagnetSearchHistory(
    @SerialName("id") val id: Long,
    @SerialName("query") val query: String,
    @SerialName("normalized_query") val normalizedQuery: String,
    @SerialName("updated_at") val updatedAt: Long,
)

/** 磁力复制/打开记录备份字段，以 sourceId + infoHash 唯一索引恢复。 */
@Keep
@Serializable
internal data class BackupMagnetDownloadRecord(
    @SerialName("id") val id: Long,
    @SerialName("source_id") val sourceId: String,
    @SerialName("info_hash") val infoHash: String,
    @SerialName("title") val title: String,
    @SerialName("detail_url") val detailUrl: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("magnet_uri") val magnetUri: String? = null,
    @SerialName("last_source_query") val lastSourceQuery: String? = null,
    @SerialName("first_used_at") val firstUsedAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long,
)

internal fun MagnetSearchHistoryData.toBackupMagnetSearchHistory(): BackupMagnetSearchHistory {
    return BackupMagnetSearchHistory(
        id = id,
        query = query,
        normalizedQuery = normalizedQuery,
        updatedAt = updatedAt
    )
}

internal fun BackupMagnetSearchHistory.toEntity(): MagnetSearchHistoryData? {
    val displayQuery = MagnetTextNormalizer.normalizeDisplayQuery(query)
    val normalized = MagnetTextNormalizer.normalizeKey(normalizedQuery.ifBlank { displayQuery })
    if (displayQuery.isBlank() || normalized.isBlank()) return null
    return MagnetSearchHistoryData(
        id = id,
        query = displayQuery,
        normalizedQuery = normalized,
        updatedAt = updatedAt
    )
}

internal fun MagnetSearchHistoryData.sameBackupContent(other: MagnetSearchHistoryData): Boolean {
    return query == other.query &&
        normalizedQuery == other.normalizedQuery &&
        updatedAt == other.updatedAt
}

internal fun MagnetDownloadRecordData.toBackupMagnetDownloadRecord(): BackupMagnetDownloadRecord {
    return BackupMagnetDownloadRecord(
        id = id,
        sourceId = sourceId,
        infoHash = infoHash,
        title = title,
        detailUrl = detailUrl,
        sizeBytes = sizeBytes,
        category = category,
        magnetUri = magnetUri,
        lastSourceQuery = lastSourceQuery,
        firstUsedAt = firstUsedAt,
        lastUsedAt = lastUsedAt
    )
}

internal fun BackupMagnetDownloadRecord.toEntity(): MagnetDownloadRecordData? {
    if (!MagnetSourceProvider.isAllowed(sourceId)) return null
    val normalizedHash = MagnetInfoHashNormalizer.normalize(infoHash) ?: return null
    val safeTitle = title.trim().ifBlank { normalizedHash }
    val safeMagnet = magnetUri
        ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
        ?: MagnetUriBuilder.build(normalizedHash, safeTitle)
        ?: return null
    return MagnetDownloadRecordData(
        id = id,
        sourceId = sourceId,
        infoHash = normalizedHash,
        title = safeTitle,
        detailUrl = detailUrl?.trim()?.takeIf { it.isNotBlank() },
        sizeBytes = sizeBytes?.takeIf { it >= 0L },
        category = category?.trim()?.takeIf { it.isNotBlank() },
        magnetUri = safeMagnet,
        lastSourceQuery = lastSourceQuery
            ?.let(MagnetTextNormalizer::normalizeDisplayQuery)
            ?.takeIf { it.isNotBlank() },
        firstUsedAt = firstUsedAt,
        lastUsedAt = lastUsedAt
    )
}

internal fun MagnetDownloadRecordData.sameBackupContent(other: MagnetDownloadRecordData): Boolean {
    return sourceId == other.sourceId &&
        infoHash == other.infoHash &&
        title == other.title &&
        detailUrl == other.detailUrl &&
        sizeBytes == other.sizeBytes &&
        category == other.category &&
        magnetUri == other.magnetUri &&
        lastSourceQuery == other.lastSourceQuery &&
        firstUsedAt == other.firstUsedAt &&
        lastUsedAt == other.lastUsedAt
}
