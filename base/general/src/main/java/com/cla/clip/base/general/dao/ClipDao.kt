package com.cla.clip.base.general.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.dao.data.LastClipData
import kotlinx.coroutines.flow.Flow

/**
 * 在 Room 数据库中，indices 属性用于在数据库表的特定列上创建索引 (Index)。索引的主要作用是加快查询速度。
 * 以下是具体的作用和原理：
 * 提高查询性能：
 * 如果没有索引，当你根据某个字段（例如 timestamp）进行查找或排序时，数据库可能需要扫描整张表（全表扫描）。
 * 有了索引，数据库可以快速定位到符合条件的行，极大地减少查找时间，特别是在通过 WHERE 子句筛选或 ORDER BY 排序时。
 * 强制唯一性 (可选)：
 * 虽然你的代码中没有使用，但 Index 注解有一个 unique = true 属性。如果设置了它，就可以确保被索引列的值在表中是唯一的，防止重复数据插入。
 * 外键约束优化：
 * 当该列作为外键使用时，添加索引可以避免在父表更新或删除时导致的子表全表扫描，从而防止性能下降甚至死锁。
 *
 * @param id 每条记录的唯一ID。
 * @param content 核心内容（文本、图片URI、链接URL）。
 * @param timestamp “最后修改”时间戳。
 * @param pinnedTime 置顶的时间戳。
 * @param link 剪贴数据中的链接
 * @param sourceAppPackage 来源应用的包名。
 */
@Entity(
    tableName = "clips",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["source_app_package"]),
        Index(value = ["content"]),
        Index(value = ["pinned_time"]),
        Index(value = ["link"]),
    ]
)
data class ClipData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "pinned_time")
    val pinnedTime: Long = 0,
    @ColumnInfo(name = "link")
    val link: String?,
    // 仅仅保留包名，用于和 SourceApp 表关联
    @ColumnInfo(name = "source_app_package")
    val sourceAppPackage: String? = null,
    // 综合搜索字段：content + appName + linkTitle 的拼接，仅用于 FTS 搜索
    @ColumnInfo(name = "search_text")
    val searchText: String,
)

/**
 * FTS虚拟表，用于对Clip表中的文本字段进行全文检索。
 * 字段必须与Clip实体中要被索引的字段完全对应。
 */
@Fts4(contentEntity = ClipData::class)
@Entity(tableName = "clips_fts")
data class ClipFts(
    @ColumnInfo(name = "search_text")
    val searchText: String
)

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
    @Query("SELECT * FROM clips WHERE content = :content AND source_app_package=:packageName LIMIT 1")
    suspend fun loadClipDetail(content: String, packageName: String): ClipDetail?

    /** 根据id查询剪贴数据 */
    @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
    suspend fun loadClipDetail(id: Long): ClipDetail?

    /**
     * 核心搜索功能：在FTS虚拟表中进行全文检索。
     *
     * 4 精确匹配
     * search_text = "支付宝"
     * 3 包含完整词
     * search_text 包含 "支付宝"
     * 2 包含前缀词
     * search_text 包含 "支付"（去掉最后一个字）
     * 1 FTS 模糊匹配
     * 包含 "支"、"付"、"宝" 任意一个
     *
     * @param query 用户的搜索词。FTS5会自动处理分词。
     * @return 匹配的Clip列表，无论新旧都会被返回。
     */
    @Query(
        """
    SELECT DISTINCT c.* FROM clips c
    JOIN clips_fts fts ON c.id = fts.rowid
    WHERE clips_fts MATCH :query
    ORDER BY 
      CASE 
        -- 1. 精确匹配
        WHEN c.search_text = :exactQuery THEN 4
        -- 2. 包含完整查询词（多字词整体出现）
        WHEN INSTR(c.search_text, :queryWord) > 0 THEN 3
        -- 3. 包含前缀词（比如搜"支付宝"找到"支付"）
        WHEN LENGTH(:queryWord) > 2 AND INSTR(c.search_text, SUBSTR(:queryWord, 1, LENGTH(:queryWord)-1)) > 0 THEN 2
        -- 4. FTS 模糊匹配（包含某个字）
        ELSE 1
      END DESC,
      c.timestamp DESC
    """
    )
    fun searchAllClips(
        query: String,           // FTS 查询（带通配符）
        exactQuery: String,      // 精确匹配用
        queryWord: String        // 包含某词用
    ): Flow<List<ClipDetail>>

    /**
     * 删除一个具体的剪贴板条目。
     * @param id 要删除的Clip id。
     */
    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteClipById(id: Long): Int

    /** 更新置顶状态 */
    @Query("UPDATE clips SET pinned_time = :pinnedTime WHERE id = :id")
    suspend fun updatePinStatus(id: Long, pinnedTime: Long)

    /** 更新时间戳 */
    @Query("UPDATE clips SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用。
     * 排序规则：
     * 1. 按照 是否置顶 (pinned_time > 0) 降序排列，保证置顶在前。
     * 2. 如果置顶了，按照 pinned_time 倒序排列（新置顶的在前）。
     * 3. 如果没置顶，按照 timestamp 倒序排列（新复制的在前）。
     */
    @Query(
        """
        SELECT * FROM clips 
        ORDER BY 
          CASE WHEN pinned_time > 0 THEN 1 ELSE 0 END DESC, 
          pinned_time DESC, 
          timestamp DESC
    """
    )
    fun loadAllClips(): PagingSource<Int, ClipDetail>

    /**
     * 清空所有剪贴板数据。
     */
    @Query("DELETE FROM clips")
    suspend fun clearAll()

    /** 获取最新的一条剪贴板记录 */
    @Query("SELECT * FROM clips ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestClip(): ClipDetail?

    /** 获取最新的一条剪贴板数据 */
    @Query(
        """
    SELECT 
        content,
        source_app_package AS sourceAppPackage
    FROM clips
    ORDER BY timestamp DESC
    LIMIT 1
    """
    )
    suspend fun loadLastClip(): LastClipData?
}