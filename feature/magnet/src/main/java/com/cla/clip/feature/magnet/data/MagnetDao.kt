package com.cla.clip.feature.magnet.data

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 磁力搜索历史用户数据。
 *
 * 只保存用户明确提交的规范化可见关键词，不保存 FTS 查询语法、高亮片段或源索引缓存；该表纳入 WebDAV 备份。
 */
@Entity(
    tableName = "magnet_search_histories",
    indices = [
        Index(value = ["normalized_query"], unique = true),
        Index(value = ["updated_at"]),
    ]
)
data class MagnetSearchHistoryData(
    @PrimaryKey(autoGenerate = true)
    /** 自增主键，只用于本机删除和备份分页游标，不作为跨设备业务身份。 */
    val id: Long = 0,

    @ColumnInfo(name = "query")
    /** 用户最后一次提交的展示关键词，已折叠为单行并限制长度。 */
    val query: String,

    @ColumnInfo(name = "normalized_query")
    /** trim + lowercase 后的业务去重键。 */
    val normalizedQuery: String,

    @ColumnInfo(name = "updated_at")
    /** 最近提交时间，UTC epoch millis。 */
    val updatedAt: Long,
)

/**
 * 磁力复制/打开记录。
 *
 * 记录只表示用户在 App 内复制或尝试打开过对应 magnet，不代表 App 内下载状态；该表纳入 WebDAV 备份。
 */
@Entity(
    tableName = "magnet_download_records",
    indices = [
        Index(value = ["source_id", "info_hash"], unique = true),
        Index(value = ["last_used_at"]),
    ]
)
data class MagnetDownloadRecordData(
    @PrimaryKey(autoGenerate = true)
    /** 本机自增主键，用于下载记录页选择、删除和分页 key。 */
    val id: Long = 0,

    @ColumnInfo(name = "source_id")
    /** 稳定来源 ID；第一版只允许 academic_torrents。 */
    val sourceId: String,

    @ColumnInfo(name = "info_hash")
    /** 规范化后的 40 位小写 BTIH infoHash。 */
    val infoHash: String,

    @ColumnInfo(name = "title")
    /** 来源条目标题，展示用；不参与唯一性判断。 */
    val title: String,

    @ColumnInfo(name = "detail_url")
    /** 合法来源详情页 URL，可为空；第一版 UI 暂不提供查看来源入口。 */
    val detailUrl: String? = null,

    @ColumnInfo(name = "size_bytes")
    /** 来源声明大小，单位字节；未知时为空。 */
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "category")
    /** 来源分类，未知时为空，UI 展示兜底文案。 */
    val category: String? = null,

    @ColumnInfo(name = "magnet_uri")
    /** 可复制的 magnet URI；恢复时缺失或异常可按 infoHash 和标题重建。 */
    val magnetUri: String,

    @ColumnInfo(name = "last_source_query")
    /** 最近一次产生或更新本记录的搜索词，已规范化为单行并限制长度。 */
    val lastSourceQuery: String? = null,

    @ColumnInfo(name = "first_used_at")
    /** 首次复制或打开时间，UTC epoch millis。 */
    val firstUsedAt: Long,

    @ColumnInfo(name = "last_used_at")
    /** 最近复制或打开时间，UTC epoch millis；列表按它倒序展示。 */
    val lastUsedAt: Long,
)

@Dao
interface MagnetDao {
    /** 观察最近磁力搜索历史，供搜索框聚焦时展示。 */
    @Query("SELECT * FROM magnet_search_histories ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecentHistories(limit: Int): Flow<List<MagnetSearchHistoryData>>

    /** 按关键词观察磁力搜索历史，LIKE 参数必须由 Repository 预先转义。 */
    @Query(
        """
        SELECT * FROM magnet_search_histories
        WHERE normalized_query LIKE '%' || :escapedKeyword || '%' ESCAPE '\'
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    fun observeMatchedHistories(escapedKeyword: String, limit: Int): Flow<List<MagnetSearchHistoryData>>

    /** 写入或刷新搜索历史。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchHistory(history: MagnetSearchHistoryData): Long

    /** 备份恢复写入搜索历史，业务唯一索引会保证重复恢复幂等。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchHistoriesForBackup(histories: List<MagnetSearchHistoryData>)

    /** 删除单条搜索历史。 */
    @Query("DELETE FROM magnet_search_histories WHERE id = :id")
    suspend fun deleteSearchHistory(id: Long): Int

    /** 清空磁力搜索历史，不影响磁力下载记录。 */
    @Query("DELETE FROM magnet_search_histories")
    suspend fun clearSearchHistories(): Int

    /** 备份导出 high-water mark。 */
    @Query("SELECT COALESCE(MAX(id), 0) FROM magnet_search_histories")
    suspend fun maxSearchHistoryIdForBackup(): Long

    /** 分页导出磁力搜索历史。 */
    @Query("SELECT * FROM magnet_search_histories WHERE id > :lastId AND id <= :maxId ORDER BY id ASC LIMIT :limit")
    suspend fun loadSearchHistoriesPageForBackup(lastId: Long, maxId: Long, limit: Int): List<MagnetSearchHistoryData>

    /** 旧兼容或分页异常兜底导出。 */
    @Query("SELECT * FROM magnet_search_histories ORDER BY id ASC")
    suspend fun loadAllSearchHistoriesForBackup(): List<MagnetSearchHistoryData>

    /** 恢复时按规范化关键词读取已有历史。 */
    @Query("SELECT * FROM magnet_search_histories WHERE normalized_query IN (:normalizedQueries)")
    suspend fun loadSearchHistoriesByQueriesForBackup(normalizedQueries: List<String>): List<MagnetSearchHistoryData>

    /** 按更新时间裁剪过旧历史，避免搜索提示无限增长。 */
    @Query(
        """
        DELETE FROM magnet_search_histories
        WHERE id NOT IN (
            SELECT id FROM magnet_search_histories
            ORDER BY updated_at DESC
            LIMIT :limit
        )
        """
    )
    suspend fun trimSearchHistoriesToLimit(limit: Int): Int

    /** 分页加载磁力下载记录，按最近使用时间倒序。 */
    @Query("SELECT * FROM magnet_download_records ORDER BY last_used_at DESC, id DESC")
    fun pagingDownloadRecords(): PagingSource<Int, MagnetDownloadRecordData>

    /** 观察磁力下载记录总数，供下载记录页标题栏和清空确认使用。 */
    @Query("SELECT COUNT(*) FROM magnet_download_records")
    fun observeDownloadRecordCount(): Flow<Int>

    /** 按当前排序读取全部磁力下载记录 id，只用于全选和清空。 */
    @Query("SELECT id FROM magnet_download_records ORDER BY last_used_at DESC, id DESC")
    suspend fun getDownloadRecordIds(): List<Long>

    /** 按 id 读取磁力下载记录。 */
    @Query("SELECT * FROM magnet_download_records WHERE id = :id")
    suspend fun getDownloadRecord(id: Long): MagnetDownloadRecordData?

    /** 批量读取磁力下载记录，供删除和动作前校验使用。 */
    @Query("SELECT * FROM magnet_download_records WHERE id IN (:ids)")
    suspend fun getDownloadRecords(ids: Set<Long>): List<MagnetDownloadRecordData>

    /** 按业务唯一键读取磁力下载记录。 */
    @Query("SELECT * FROM magnet_download_records WHERE source_id = :sourceId AND info_hash = :infoHash LIMIT 1")
    suspend fun getDownloadRecordByKey(sourceId: String, infoHash: String): MagnetDownloadRecordData?

    /** 常规写入或更新磁力下载记录。 */
    @Upsert
    suspend fun upsertDownloadRecord(record: MagnetDownloadRecordData): Long

    /** 备份恢复批量写入磁力下载记录。 */
    @Upsert
    suspend fun upsertDownloadRecordsForBackup(records: List<MagnetDownloadRecordData>)

    /** 删除单条磁力下载记录。 */
    @Query("DELETE FROM magnet_download_records WHERE id = :id")
    suspend fun deleteDownloadRecord(id: Long): Int

    /** 删除选中磁力下载记录。 */
    @Query("DELETE FROM magnet_download_records WHERE id IN (:ids)")
    suspend fun deleteDownloadRecords(ids: Set<Long>): Int

    /** 清空磁力下载记录，不影响搜索历史。 */
    @Query("DELETE FROM magnet_download_records")
    suspend fun clearDownloadRecords(): Int

    /** 备份导出 high-water mark。 */
    @Query("SELECT COALESCE(MAX(id), 0) FROM magnet_download_records")
    suspend fun maxDownloadRecordIdForBackup(): Long

    /** 分页导出磁力下载记录。 */
    @Query("SELECT * FROM magnet_download_records WHERE id > :lastId AND id <= :maxId ORDER BY id ASC LIMIT :limit")
    suspend fun loadDownloadRecordsPageForBackup(lastId: Long, maxId: Long, limit: Int): List<MagnetDownloadRecordData>

    /** 旧兼容或分页异常兜底导出。 */
    @Query("SELECT * FROM magnet_download_records ORDER BY id ASC")
    suspend fun loadAllDownloadRecordsForBackup(): List<MagnetDownloadRecordData>

    /** 恢复时按业务键读取已有记录。 */
    @Query(
        """
        SELECT * FROM magnet_download_records
        WHERE source_id = :sourceId AND info_hash IN (:infoHashes)
        """
    )
    suspend fun loadDownloadRecordsBySourceAndHashesForBackup(
        sourceId: String,
        infoHashes: List<String>
    ): List<MagnetDownloadRecordData>
}
