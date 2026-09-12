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
}
