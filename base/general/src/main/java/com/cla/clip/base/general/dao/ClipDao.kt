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
    @Query("SELECT * FROM clips WHERE content = :content LIMIT 1")
    suspend fun getClipByContent(content: String): ClipData?

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
    @Query("UPDATE clips SET pinned_time = :pinnedTime WHERE id = :id")
    suspend fun updatePinStatus(id: Long, pinnedTime: Long)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用。
     * 排序规则：
     * 1. 按照 是否置顶 (pinned_time > 0) 降序排列，保证置顶在前。
     * 2. 如果置顶了，按照 pinned_time 倒序排列（新置顶的在前）。
     * 3. 如果没置顶，按照 timestamp 倒序排列（新复制的在前）。
     */
    @Query("""
        SELECT * FROM clips 
        ORDER BY 
          CASE WHEN pinned_time > 0 THEN 1 ELSE 0 END DESC, 
          pinned_time DESC, 
          timestamp DESC
    """)
    fun loadAllClips(): PagingSource<Int, ClipWithSourceApp>

    /**
     * 清空所有剪贴板数据。
     */
    @Query("DELETE FROM clips")
    suspend fun clearAll()

    /** 获取最新的一条剪贴板记录 */
    @Query("SELECT * FROM clips ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestClip(): ClipWithSourceApp?
}