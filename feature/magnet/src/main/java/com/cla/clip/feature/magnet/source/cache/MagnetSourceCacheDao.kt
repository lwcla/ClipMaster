package com.cla.clip.feature.magnet.source.cache

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * 可重建的磁力源索引缓存条目。
 *
 * 该表只放在 cacheDir 独立库中，允许破坏性迁移和清理；不进入主库，也不进入 WebDAV 备份。
 */
@Entity(
    tableName = "magnet_source_items",
    indices = [
        Index(value = ["source_id", "info_hash"], unique = true),
        Index(value = ["source_id", "updated_at"]),
    ]
)
data class MagnetSourceItemData(
    @PrimaryKey(autoGenerate = true)
    /** 缓存库本地自增 id，只用于 Paging key 和 FTS rowid。 */
    val id: Long = 0,

    @ColumnInfo(name = "source_id")
    /** 稳定来源 ID；第一版只写入 academic_torrents。 */
    val sourceId: String,

    @ColumnInfo(name = "info_hash")
    /** 规范化后的 40 位 BTIH infoHash。 */
    val infoHash: String,

    @ColumnInfo(name = "title")
    /** 来源标题，展示和搜索使用。 */
    val title: String,

    @ColumnInfo(name = "detail_url")
    /** 来源详情页 URL。 */
    val detailUrl: String? = null,

    @ColumnInfo(name = "size_bytes")
    /** 来源声明大小，单位字节。 */
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "category")
    /** 来源分类。 */
    val category: String? = null,

    @ColumnInfo(name = "description")
    /** 来源描述，仅用于本地搜索，不在列表中过度展示。 */
    val description: String? = null,

    @ColumnInfo(name = "updated_at")
    /** 本条缓存写入时间，UTC epoch millis。 */
    val updatedAt: Long,

    @ColumnInfo(name = "search_text")
    /** 综合搜索字段，由标题、分类、描述和 infoHash 拼接。 */
    val searchText: String,
)

/** 磁力源索引 FTS 表，和 `MagnetSourceItemData.searchText` 同步。 */
@Fts4(contentEntity = MagnetSourceItemData::class)
@Entity(tableName = "magnet_source_items_fts")
data class MagnetSourceItemFts(
    @ColumnInfo(name = "search_text")
    val searchText: String
)

/** 磁力源缓存元数据。 */
@Entity(tableName = "magnet_source_cache_meta")
data class MagnetSourceCacheMetaData(
    @PrimaryKey
    @ColumnInfo(name = "source_id")
    /** 稳定来源 ID。 */
    val sourceId: String,

    @ColumnInfo(name = "etag")
    /** 上次响应 ETag，可为空。 */
    val etag: String? = null,

    @ColumnInfo(name = "last_modified")
    /** 上次响应 Last-Modified，可为空。 */
    val lastModified: String? = null,

    @ColumnInfo(name = "sync_started_at")
    /** 最近同步开始时间。 */
    val syncStartedAt: Long = 0,

    @ColumnInfo(name = "sync_completed_at")
    /** 最近成功同步完成时间。 */
    val syncCompletedAt: Long = 0,

    @ColumnInfo(name = "item_count")
    /** 最近完整索引条目数。 */
    val itemCount: Int = 0,

    @ColumnInfo(name = "cache_size_bytes")
    /** 最近估算缓存大小，单位字节。 */
    val cacheSizeBytes: Long = 0,

    @ColumnInfo(name = "last_failure_reason")
    /** 最近失败 reasonCode。 */
    val lastFailureReason: String? = null,

    @ColumnInfo(name = "complete")
    /** 当前缓存是否完成可搜索。 */
    val complete: Boolean = false,
)

/** 磁力源搜索结果投影。 */
data class MagnetSourceSearchRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "info_hash") val infoHash: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "detail_url") val detailUrl: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface MagnetSourceCacheDao {
    /** 读取来源缓存元数据。 */
    @Query("SELECT * FROM magnet_source_cache_meta WHERE source_id = :sourceId LIMIT 1")
    suspend fun getMeta(sourceId: String): MagnetSourceCacheMetaData?

    /** 写入来源缓存元数据。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: MagnetSourceCacheMetaData)

    /** 统计当前来源索引条数。 */
    @Query("SELECT COUNT(*) FROM magnet_source_items WHERE source_id = :sourceId")
    suspend fun countItems(sourceId: String): Int

    /** 清空某个来源索引，元数据由调用方单独维护。 */
    @Query("DELETE FROM magnet_source_items WHERE source_id = :sourceId")
    suspend fun clearItems(sourceId: String): Int

    /** 批量写入索引条目。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MagnetSourceItemData>)

    /** 关键词为空时按更新时间稳定分页，主要用于同步完成后的默认浏览兜底。 */
    @Query(
        """
        SELECT id, source_id, info_hash, title, detail_url, size_bytes, category, description, updated_at
        FROM magnet_source_items
        WHERE source_id = :sourceId
        ORDER BY updated_at DESC, title COLLATE NOCASE ASC, info_hash ASC
        """
    )
    fun pageAll(sourceId: String): PagingSource<Int, MagnetSourceSearchRow>

    /** FTS + LIKE 组合搜索，优先相关性，再按稳定字段排序。 */
    @Transaction
    @Query(
        """
        SELECT * FROM (
            SELECT c.id, c.source_id, c.info_hash, c.title, c.detail_url, c.size_bytes, c.category, c.description, c.updated_at
            FROM magnet_source_items c
            JOIN magnet_source_items_fts ON c.id = magnet_source_items_fts.rowid
            WHERE c.source_id = :sourceId
              AND magnet_source_items_fts MATCH :ftsQuery
            UNION
            SELECT c.id, c.source_id, c.info_hash, c.title, c.detail_url, c.size_bytes, c.category, c.description, c.updated_at
            FROM magnet_source_items c
            WHERE c.source_id = :sourceId
              AND c.search_text LIKE '%' || :likeKeyword || '%' ESCAPE '\'
        ) AS c
        ORDER BY
            CASE
                WHEN c.title = :exactQuery THEN 5
                WHEN INSTR(c.title, :rankKeyword) > 0 THEN 4
                WHEN c.category IS NOT NULL AND INSTR(c.category, :rankKeyword) > 0 THEN 3
                WHEN c.description IS NOT NULL AND INSTR(c.description, :rankKeyword) > 0 THEN 2
                ELSE 1
            END DESC,
            c.title COLLATE NOCASE ASC,
            c.info_hash ASC
        """
    )
    fun search(
        sourceId: String,
        ftsQuery: String,
        exactQuery: String,
        likeKeyword: String,
        rankKeyword: String,
    ): PagingSource<Int, MagnetSourceSearchRow>

    /** FTS 无法构造时退回普通 LIKE，避免符号输入导致 MATCH 语法错误。 */
    @Query(
        """
        SELECT id, source_id, info_hash, title, detail_url, size_bytes, category, description, updated_at
        FROM magnet_source_items
        WHERE source_id = :sourceId
          AND search_text LIKE '%' || :likeKeyword || '%' ESCAPE '\'
        ORDER BY
            CASE
                WHEN title = :exactQuery THEN 5
                WHEN INSTR(title, :rankKeyword) > 0 THEN 4
                WHEN category IS NOT NULL AND INSTR(category, :rankKeyword) > 0 THEN 3
                WHEN description IS NOT NULL AND INSTR(description, :rankKeyword) > 0 THEN 2
                ELSE 1
            END DESC,
            title COLLATE NOCASE ASC,
            info_hash ASC
        """
    )
    fun searchLike(
        sourceId: String,
        exactQuery: String,
        likeKeyword: String,
        rankKeyword: String,
    ): PagingSource<Int, MagnetSourceSearchRow>
}

@Database(
    entities = [
        MagnetSourceItemData::class,
        MagnetSourceItemFts::class,
        MagnetSourceCacheMetaData::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MagnetSourceCacheDatabase : RoomDatabase() {
    abstract fun magnetSourceCacheDao(): MagnetSourceCacheDao
}
