package com.cla.clip.feature.magnet.source.cache

import com.cla.clip.feature.magnet.MAGNET_SOURCE_ACADEMIC_TORRENTS

/** 磁力源索引状态 reasonCode。 */
enum class MagnetSourceStatusReason(val code: String) {
    NotSynced("not_synced"),
    Syncing("syncing"),
    Ready("ready"),
    NetworkFailed("network_failed"),
    ParseFailed("parse_failed"),
    CacheCleared("cache_cleared"),
    EmptyIndex("empty_index"),
    UserCancelled("user_cancelled"),
    NotModified("not_modified"),
    InsufficientSpace("insufficient_space"),
    InvalidInfoHash("invalid_info_hash"),
    NetworkTimeout("network_timeout"),
    Cooldown("cooldown"),
}

/** 磁力源缓存状态。 */
data class MagnetSourceCacheState(
    val sourceId: String = MAGNET_SOURCE_ACADEMIC_TORRENTS,
    val reason: MagnetSourceStatusReason = MagnetSourceStatusReason.NotSynced,
    val itemCount: Int = 0,
    val syncCompletedAt: Long = 0,
    val cacheSizeBytes: Long = 0,
    val syncing: Boolean = false,
)

/** 同步进度，只包含低敏数量和阶段，不包含标题、hash 或 URL。 */
data class MagnetSourceSyncProgress(
    val taskId: String,
    val sourceId: String,
    val parsedCount: Int = 0,
    val importedCount: Int = 0,
    val phase: MagnetSourceSyncPhase = MagnetSourceSyncPhase.Idle,
    val reason: MagnetSourceStatusReason? = null,
)

enum class MagnetSourceSyncPhase {
    Idle,
    Checking,
    Downloading,
    Parsing,
    Importing,
    Completed,
    Failed,
    Cancelled,
}

/** Academic Torrents XML 中解析出的单条索引。 */
data class AcademicTorrentsEntry(
    val infoHash: String,
    val title: String,
    val detailUrl: String?,
    val sizeBytes: Long?,
    val category: String?,
    val description: String?,
)

/** 搜索结果领域模型。 */
data class MagnetSearchResult(
    val id: Long,
    val sourceId: String,
    val infoHash: String,
    val title: String,
    val detailUrl: String?,
    val sizeBytes: Long?,
    val category: String?,
    val description: String?,
    val magnetUri: String,
)
