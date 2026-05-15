package com.cla.clip.base.general.dao

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
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
 * @param isFolded 是否折叠隐藏。
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
        Index(value = ["is_folded"]),
        Index(value = ["link"]),
    ]
)
data class ClipData(
    @PrimaryKey(autoGenerate = true)
    /** 剪贴板记录自增主键，作为详情页导航和删除/置顶操作的稳定 id。 */
    val id: Long = 0,

    @ColumnInfo(name = "content")
    /** 剪贴板原始文本内容，不能为空；去重逻辑会结合来源应用包名比较该字段。 */
    val content: String,

    @ColumnInfo(name = "timestamp")
    /** 记录最近一次写入或刷新时间，单位毫秒；列表默认按它倒序展示。 */
    val timestamp: Long,

    @ColumnInfo(name = "pinned_time")
    /** 置顶时间，单位毫秒；0 表示未置顶，大于 0 时列表优先按置顶时间排序。 */
    val pinnedTime: Long = 0,

    @ColumnInfo(name = "is_folded")
    /** 是否折叠隐藏；true 表示从普通列表和普通搜索移出，只在折叠数据页和折叠搜索中展示。 */
    val isFolded: Boolean = false,

    @ColumnInfo(name = "link")
    /** 从剪贴内容中提取出的首个可预览链接，可能为空；用于关联 LinkPreviewData 和展示提取入口。 */
    val link: String?,

    @ColumnInfo(name = "source_app_package")
    /** 来源应用包名，可能为空；仅保存包名并通过 SourceAppData 延迟关联应用名称、图标和主色。 */
    val sourceAppPackage: String? = null,

    @ColumnInfo(name = "search_text")
    /** 综合搜索字段，由内容、来源 App 名称和链接标题拼接而成，仅用于 FTS/LIKE 搜索，不直接展示给用户。 */
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
    /** FTS 虚拟表索引内容，必须与 ClipData.searchText 保持同步。 */
    val searchText: String
)

@Dao
/**
 * 剪贴板记录 DAO。
 *
 * 负责剪贴内容写入、去重查询、分页列表、全文搜索、置顶、删除和最近记录读取；搜索页和列表页都依赖这里的排序契约。
 */
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
    @Transaction
    @Query("SELECT * FROM clips WHERE content = :content AND source_app_package=:packageName LIMIT 1")
    suspend fun loadClipDetail(content: String, packageName: String): ClipDetail?

    /** 根据id查询剪贴数据 */
    @Transaction
    @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
    suspend fun loadClipDetail(id: Long): ClipDetail?

    /**
     * 按链接加载所有关联剪贴记录。
     *
     * WebView 后置补全链接预览时需要同步刷新这些记录的搜索索引文本；使用关系查询可以同时拿到来源 App 名称，
     * 避免 Repository 层为了重建 search_text 再发起多次查询。
     */
    @Transaction
    @Query("SELECT * FROM clips WHERE link = :link")
    suspend fun loadClipDetailsByLink(link: String): List<ClipDetail>

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
    @Transaction
    @Query(
        """
    SELECT DISTINCT c.* FROM clips c
    JOIN clips_fts fts ON c.id = fts.rowid
    WHERE clips_fts MATCH :query
      AND c.is_folded = 0
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
        /** FTS 查询表达式，通常由 Repository 清洗并追加通配符。 */
        query: String,

        /** 精确匹配排序使用的原始查询文本。 */
        exactQuery: String,

        /** 包含匹配和前缀匹配使用的核心查询词。 */
        queryWord: String
    ): Flow<List<ClipDetail>>

    /**
     * 按关键词、时间范围和来源 App 分页搜索剪贴记录。
     *
     * 这个查询供搜索页使用：关键词优先走 FTS 虚拟表，同时并入 `LIKE` 子串命中集合。
     *
     * 中文连续文本在默认 FTS 分词下可能被当成一个长 token，例如搜索“来源”时不一定能命中
     * “来源应用的包名”；因此这里额外使用 `search_text LIKE '%关键词%'` 作为中文和 URL 片段的兜底。
     * UNION 会去掉 FTS 与 LIKE 同时命中的重复记录，代价是关键词搜索比纯 FTS 多一次主表扫描。
     * 两个 UNION 分支都必须带折叠过滤，否则任一命中分支都会把另一范围的数据混入结果。
     *
     * 排序先保持列表页的置顶优先规则，再在同一置顶分组内按搜索命中质量和时间排序，避免搜索页与列表页的置顶语义割裂。
     *
     * @param query 清洗后的 FTS 查询语句，外层 Repository 负责规避空查询和特殊字符导致的 MATCH 语法错误。
     * @param exactQuery 精确匹配用原始关键词。
     * @param queryWord 包含匹配和前缀匹配用的核心词。
     * @param likeKeyword 普通子串匹配用关键词，保留用户输入的连续文本，用来补齐中文模糊搜索。
     * @param startTime 起始时间戳，单位毫秒；为 null 时不限制开始时间。
     * @param endTime 结束时间戳，单位毫秒；为 null 时不限制结束时间，非 null 时使用左闭右开区间。
     * @param sourceAppPackageCount 已选来源 App 数量；为 0 时不过滤来源。
     * @param sourceAppPackages 已选来源 App 包名列表；非空时命中任一包名即可返回。
     * @param isFolded 搜索范围过滤；普通搜索传 false，折叠搜索传 true，避免折叠数据从普通搜索泄漏。
     */
    @Transaction
    @Query(
        """
    SELECT * FROM (
      SELECT c.* FROM clips c
      JOIN clips_fts fts ON c.id = fts.rowid
      WHERE clips_fts MATCH :query
        AND c.is_folded = :isFolded
        AND (:startTime IS NULL OR c.timestamp >= :startTime)
        AND (:endTime IS NULL OR c.timestamp < :endTime)
        AND (:sourceAppPackageCount = 0 OR c.source_app_package IN (:sourceAppPackages))
      UNION
      SELECT c.* FROM clips c
      WHERE c.search_text LIKE '%' || :likeKeyword || '%'
        AND c.is_folded = :isFolded
        AND (:startTime IS NULL OR c.timestamp >= :startTime)
        AND (:endTime IS NULL OR c.timestamp < :endTime)
        AND (:sourceAppPackageCount = 0 OR c.source_app_package IN (:sourceAppPackages))
    ) AS c
    ORDER BY
      CASE WHEN c.pinned_time > 0 THEN 1 ELSE 0 END DESC,
      CASE
        WHEN c.search_text = :exactQuery THEN 4
        WHEN INSTR(c.search_text, :likeKeyword) > 0 THEN 3
        WHEN LENGTH(:queryWord) > 2 AND INSTR(c.search_text, SUBSTR(:queryWord, 1, LENGTH(:queryWord)-1)) > 0 THEN 2
        ELSE 1
      END DESC,
      c.pinned_time DESC,
      c.timestamp DESC
    """
    )
    fun searchClipsByKeyword(
        query: String,
        exactQuery: String,
        queryWord: String,
        likeKeyword: String,
        startTime: Long?,
        endTime: Long?,
        sourceAppPackageCount: Int,
        sourceAppPackages: List<String>,
        isFolded: Boolean,
    ): PagingSource<Int, ClipDetail>

    /**
     * 在没有关键词时按筛选条件分页加载剪贴记录。
     *
     * 关键词为空不能执行 FTS MATCH，否则 SQLite 会抛语法异常；因此搜索页清空输入时走这个查询，
     * 只保留时间和来源 App 多选条件，并完全沿用列表页排序。
     *
     * @param isFolded 搜索范围过滤；普通搜索传 false，折叠搜索传 true。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM clips
        WHERE is_folded = :isFolded
          AND (:startTime IS NULL OR timestamp >= :startTime)
          AND (:endTime IS NULL OR timestamp < :endTime)
          AND (:sourceAppPackageCount = 0 OR source_app_package IN (:sourceAppPackages))
        ORDER BY
          CASE WHEN pinned_time > 0 THEN 1 ELSE 0 END DESC,
          pinned_time DESC,
          timestamp DESC
    """
    )
    fun searchClipsByFilters(
        startTime: Long?,
        endTime: Long?,
        sourceAppPackageCount: Int,
        sourceAppPackages: List<String>,
        isFolded: Boolean,
    ): PagingSource<Int, ClipDetail>

    /**
     * FTS 查询无法构造时的兜底搜索。
     *
     * 某些输入只包含标点或 FTS 特殊字符，直接拼进 MATCH 会失败；此处退回到普通 LIKE，
     * 牺牲一点性能换取搜索框对 URL、符号片段等输入的稳定响应，同时保留来源 App 多选过滤。
     *
     * @param isFolded 搜索范围过滤；普通搜索传 false，折叠搜索传 true。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM clips
        WHERE is_folded = :isFolded
          AND search_text LIKE '%' || :keyword || '%'
          AND (:startTime IS NULL OR timestamp >= :startTime)
          AND (:endTime IS NULL OR timestamp < :endTime)
          AND (:sourceAppPackageCount = 0 OR source_app_package IN (:sourceAppPackages))
        ORDER BY
          CASE WHEN pinned_time > 0 THEN 1 ELSE 0 END DESC,
          pinned_time DESC,
          timestamp DESC
    """
    )
    fun searchClipsByLike(
        keyword: String,
        startTime: Long?,
        endTime: Long?,
        sourceAppPackageCount: Int,
        sourceAppPackages: List<String>,
        isFolded: Boolean,
    ): PagingSource<Int, ClipDetail>

    /**
     * 删除一个具体的剪贴板条目。
     * @param id 要删除的Clip id。
     */
    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteClipById(id: Long): Int

    /** 更新置顶状态 */
    @Query("UPDATE clips SET pinned_time = :pinnedTime WHERE id = :id")
    suspend fun updatePinStatus(id: Long, pinnedTime: Long)

    /** 更新折叠状态；折叠只改变普通/折叠范围可见性，不影响置顶、时间和内容。 */
    @Query("UPDATE clips SET is_folded = :isFolded WHERE id = :id")
    suspend fun updateFoldStatus(id: Long, isFolded: Boolean)

    /** 更新时间戳 */
    @Query("UPDATE clips SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用。
     * 排序规则：
     * 1. 按照 是否置顶 (pinned_time > 0) 降序排列，保证置顶在前。
     * 2. 如果置顶了，按照 pinned_time 倒序排列（新置顶的在前）。
     * 3. 如果没置顶，按照 timestamp 倒序排列（新复制的在前）。
     *
     * @param isFolded 查询范围；false 为普通列表，true 为折叠列表。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM clips 
        WHERE is_folded = :isFolded
        ORDER BY 
          CASE WHEN pinned_time > 0 THEN 1 ELSE 0 END DESC, 
          pinned_time DESC, 
          timestamp DESC
    """
    )
    fun loadClipsByFoldState(isFolded: Boolean): PagingSource<Int, ClipDetail>

    /** 轻量统计折叠记录数量，供“我的”入口展示，不通过加载分页列表统计。 */
    @Query("SELECT COUNT(*) FROM clips WHERE is_folded = 1")
    fun observeFoldedClipCount(): Flow<Int>

    /**
     * 清空所有剪贴板数据。
     */
    @Query("DELETE FROM clips")
    suspend fun clearAll()

    /** 获取最新的一条剪贴板记录 */
    @Transaction
    @Query("SELECT * FROM clips ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestClip(): ClipDetail?

    /** 获取最新的一条未折叠剪贴板数据，用于剪贴采集去重；折叠数据不应阻止用户重新复制同内容后回到普通列表。 */
    @Query(
        """
    SELECT 
        content,
        source_app_package AS sourceAppPackage
    FROM clips
    WHERE is_folded = 0
    ORDER BY timestamp DESC
    LIMIT 1
    """
    )
    suspend fun loadLastClip(): LastClipData?
}
