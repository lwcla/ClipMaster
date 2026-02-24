package com.cla.clip.base.general.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.cla.clip.base.general.dao.data.ClipData
import com.cla.clip.base.general.dao.data.ClipWithSourceApp
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    /**
     * 更新或插入一个Clip条目。这是所有数据写入的基础，使用更高效的 @Upsert。
     * @param clip 要更新或插入的Clip对象。
     * @return 更新或插入条目的ID。
     */
    @Upsert
    suspend fun upsertClip(clip: ClipData): Long

    /**
     * Get 基础查询：查找是否存在相同内容的最新条目。这是去重逻辑的核心查询，必须高效。
     *
     * @param content 要查询的内容。FTS5会自动处理分词和匹配，所以这里直接传入原始内容即可。
     * @return
     */
    @Query("SELECT * FROM clips WHERE content = :content AND is_latest = 1 LIMIT 1")
    suspend fun getClipByContent(content: String): ClipData?

    /**
     * 将指定分组中的所有条目的 isLatest 标志设置为 false。
     * 这是在插入新版本之前必须调用的步骤。
     * @param groupId 要重置的分组ID。
     */
    @Query("UPDATE clips SET is_latest = 0 WHERE group_id = :groupId")
    suspend fun resetLatestFlagForGroup(groupId: Long)

    /**
     * 获取主屏幕上显示的剪贴板列表。
     * 只获取每个分组中最新的、未被置顶的条目，按时间戳降序排列。
     * @return 一个可观察的Clip列表Flow。
     */
    @Query("SELECT * FROM clips WHERE is_latest = 1 AND is_pinned = 0 ORDER BY timestamp DESC")
    fun getLatestClips(): Flow<List<ClipWithSourceApp>>

    /**
     * 获取所有置顶的剪贴板列表。
     * 只获取每个分组中最新的、被置顶的条目，按时间戳降序排列。
     * @return 一个可观察的置顶Clip列表Flow。
     */
    @Query("SELECT * FROM clips WHERE is_latest = 1 AND is_pinned = 1 ORDER BY timestamp DESC")
    fun getPinnedClips(): Flow<List<ClipWithSourceApp>>

    /**
     * 根据分组ID获取一个条目的所有历史版本（包括最新版），按时间戳降序排列。
     * @param groupId 要查询的分组ID。
     * @return 该分组的所有历史记录列表。
     */
    @Query("SELECT * FROM clips WHERE group_id = :groupId ORDER BY timestamp DESC")
    suspend fun getHistoryForGroup(groupId: Long): List<ClipWithSourceApp>

    /**
     * 核心搜索功能：在FTS虚拟表中进行全文检索。
     * @param query 用户的搜索词。FTS5会自动处理分词。
     * @return 匹配的Clip列表，无论新旧都会被返回。
     */
    @Query("SELECT c.* FROM clips c JOIN clips_fts fts ON c.id = fts.rowid WHERE fts.clips_fts MATCH :query")
    fun searchAllClips(query: String): Flow<List<ClipWithSourceApp>>

    /**
     * 删除一个具体的剪贴板条目。
     * @param id 要删除的Clip id。
     */
    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteClipById(id: Long)

    /** 更新置顶状态 */
    @Query("UPDATE clips SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: Long, isPinned: Boolean)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用，返回一个 PagingSource 对象。
     * 排序规则：置顶在前，其余按时间倒序。
     *
     * @return
     */
    @Query("SELECT * FROM clips WHERE is_latest = 1 ORDER BY is_pinned DESC, timestamp DESC")
    fun loadAllClips(): PagingSource<Int, ClipWithSourceApp>

    /**
     * 清空所有剪贴板数据。
     */
    @Query("DELETE FROM clips")
    suspend fun clearAll()

    /** 获取最新的一条剪贴板记录 */
    @Query("SELECT * FROM clips WHERE is_latest = 1 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestClip(): ClipWithSourceApp?
}