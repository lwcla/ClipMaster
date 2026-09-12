package com.cla.clip.feature.torrent.search.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 书签 DAO 只定义数据库读写契约，领域映射和输入校验由 Repository 负责。 */
@Dao
internal interface BookmarkedTorrentDao {
    /** 按最近书签时间倒序观察全部书签。 */
    @Query("SELECT * FROM torrent_search_bookmarks ORDER BY bookmarked_at_epoch_millis DESC")
    fun observeAll(): Flow<List<BookmarkedTorrentEntity>>

    /** 精确观察单个 infoHash 是否存在，供详情页避免订阅整表。 */
    @Query("SELECT EXISTS(SELECT 1 FROM torrent_search_bookmarks WHERE info_hash = :infoHash)")
    fun observeExistsByInfoHash(infoHash: String): Flow<Boolean>

    /** 按 infoHash 插入或替换书签，允许同名但不同 hash 共存。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkedTorrentEntity)

    /** 删除稳定 infoHash 对应书签并返回影响行数。 */
    @Query("DELETE FROM torrent_search_bookmarks WHERE info_hash = :infoHash")
    suspend fun deleteByInfoHash(infoHash: String): Int

    /** 清空全部书签并返回影响行数。 */
    @Query("DELETE FROM torrent_search_bookmarks")
    suspend fun clear(): Int

    /** 读取导出开始时的最大书签时间；空表返回 -1。 */
    @Query("SELECT COALESCE(MAX(bookmarked_at_epoch_millis), -1) FROM torrent_search_bookmarks")
    suspend fun readBackupHighWaterMark(): Long

    /** 在固定时间上界内按时间与主键游标分页，避免备份一次性加载整表。 */
    @Query(
        """
        SELECT * FROM torrent_search_bookmarks
        WHERE bookmarked_at_epoch_millis <= :highWaterMark
          AND (
            bookmarked_at_epoch_millis > :cursorTime
            OR (bookmarked_at_epoch_millis = :cursorTime AND info_hash > :cursorKey)
          )
        ORDER BY bookmarked_at_epoch_millis ASC, info_hash ASC
        LIMIT :limit
        """
    )
    /** 执行书签备份游标分页查询。 */
    suspend fun readBackupPage(
        /** 导出开始时固定的最大书签时间。 */
        highWaterMark: Long,
        /** 上一页最后一条的时间游标。 */
        cursorTime: Long,
        /** 同时间记录的稳定主键游标。 */
        cursorKey: String,
        /** 单页最大记录数。 */
        limit: Int,
    ): List<BookmarkedTorrentEntity>

    /** 按稳定主键批量读取本地行，供恢复冲突判断。 */
    @Query("SELECT * FROM torrent_search_bookmarks WHERE info_hash IN (:infoHashes)")
    suspend fun findByInfoHashes(infoHashes: List<String>): List<BookmarkedTorrentEntity>

    /** 在单次 Room 调用中幂等插入或替换一批已校验恢复行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<BookmarkedTorrentEntity>)
}

/** 搜索历史 DAO 维护唯一稳定查询和最近记录裁剪。 */
@Dao
internal interface TorrentSearchHistoryDao {
    /** 按最近更新时间倒序观察固定上限历史。 */
    @Query("SELECT * FROM torrent_search_histories ORDER BY updated_at_epoch_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TorrentSearchHistoryEntity>>

    /** 按规范化查询插入或替换历史。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TorrentSearchHistoryEntity)

    /** 删除稳定规范化查询对应历史并返回影响行数。 */
    @Query("DELETE FROM torrent_search_histories WHERE normalized_query = :normalizedQuery")
    suspend fun deleteByNormalizedQuery(normalizedQuery: String): Int

    /** 清空全部搜索历史并返回影响行数。 */
    @Query("DELETE FROM torrent_search_histories")
    suspend fun clear(): Int

    /** 仅保留更新时间最新的 limit 条，避免历史无限增长。 */
    @Query(
        """
        DELETE FROM torrent_search_histories
        WHERE normalized_query NOT IN (
            SELECT normalized_query FROM torrent_search_histories
            ORDER BY updated_at_epoch_millis DESC
            LIMIT :limit
        )
        """
    )
    /** 执行固定上限裁剪并返回删除行数，供 Repository 记录低敏诊断。 */
    suspend fun trimToLimit(limit: Int): Int

    /** 读取导出开始时的最大历史更新时间；空表返回 -1。 */
    @Query("SELECT COALESCE(MAX(updated_at_epoch_millis), -1) FROM torrent_search_histories")
    suspend fun readBackupHighWaterMark(): Long

    /** 在固定时间上界内按时间与主键游标分页，避免备份一次性加载整表。 */
    @Query(
        """
        SELECT * FROM torrent_search_histories
        WHERE updated_at_epoch_millis <= :highWaterMark
          AND (
            updated_at_epoch_millis > :cursorTime
            OR (updated_at_epoch_millis = :cursorTime AND normalized_query > :cursorKey)
          )
        ORDER BY updated_at_epoch_millis ASC, normalized_query ASC
        LIMIT :limit
        """
    )
    /** 执行搜索历史备份游标分页查询。 */
    suspend fun readBackupPage(
        /** 导出开始时固定的最大历史时间。 */
        highWaterMark: Long,
        /** 上一页最后一条的时间游标。 */
        cursorTime: Long,
        /** 同时间记录的稳定主键游标。 */
        cursorKey: String,
        /** 单页最大记录数。 */
        limit: Int,
    ): List<TorrentSearchHistoryEntity>

    /** 按稳定主键批量读取本地历史，供恢复冲突判断。 */
    @Query("SELECT * FROM torrent_search_histories WHERE normalized_query IN (:normalizedQueries)")
    suspend fun findByNormalizedQueries(normalizedQueries: List<String>): List<TorrentSearchHistoryEntity>

    /** 在单次 Room 调用中幂等插入或替换一批已校验恢复行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TorrentSearchHistoryEntity>)
}

/** 已查看 DAO 以 infoHash 替换最近时间并提供倒序观察。 */
@Dao
internal interface ViewedTorrentDao {
    /** 按最近查看时间倒序观察全部已查看身份。 */
    @Query("SELECT * FROM torrent_search_viewed ORDER BY viewed_at_epoch_millis DESC")
    fun observeAll(): Flow<List<ViewedTorrentEntity>>

    /** 插入或刷新同 infoHash 的最近查看时间。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ViewedTorrentEntity)

    /** 清空全部已查看记录并返回影响行数。 */
    @Query("DELETE FROM torrent_search_viewed")
    suspend fun clear(): Int

    /** 读取导出开始时的最大查看时间；空表返回 -1。 */
    @Query("SELECT COALESCE(MAX(viewed_at_epoch_millis), -1) FROM torrent_search_viewed")
    suspend fun readBackupHighWaterMark(): Long

    /** 在固定时间上界内按时间与主键游标分页，避免备份一次性加载整表。 */
    @Query(
        """
        SELECT * FROM torrent_search_viewed
        WHERE viewed_at_epoch_millis <= :highWaterMark
          AND (
            viewed_at_epoch_millis > :cursorTime
            OR (viewed_at_epoch_millis = :cursorTime AND info_hash > :cursorKey)
          )
        ORDER BY viewed_at_epoch_millis ASC, info_hash ASC
        LIMIT :limit
        """
    )
    /** 执行已查看备份游标分页查询。 */
    suspend fun readBackupPage(
        /** 导出开始时固定的最大查看时间。 */
        highWaterMark: Long,
        /** 上一页最后一条的时间游标。 */
        cursorTime: Long,
        /** 同时间记录的稳定主键游标。 */
        cursorKey: String,
        /** 单页最大记录数。 */
        limit: Int,
    ): List<ViewedTorrentEntity>

    /** 按稳定主键批量读取本地行，供恢复冲突判断。 */
    @Query("SELECT * FROM torrent_search_viewed WHERE info_hash IN (:infoHashes)")
    suspend fun findByInfoHashes(infoHashes: List<String>): List<ViewedTorrentEntity>

    /** 在单次 Room 调用中幂等插入或替换一批已校验恢复行。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ViewedTorrentEntity>)
}
