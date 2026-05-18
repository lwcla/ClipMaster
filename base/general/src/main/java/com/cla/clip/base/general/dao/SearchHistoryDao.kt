package com.cla.clip.base.general.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史数据。
 *
 * 该表只保存搜索关键词，不保存时间筛选、来源 App 筛选或结果列表状态；普通搜索和折叠搜索通过 `isFolded`
 * 完全隔离，避免用户在普通列表里看到折叠范围的历史提示。规范化关键词用于同范围去重，
 * 展示时仍使用用户最后一次提交的原始写法。
 */
@Entity(
    tableName = "search_histories",
    indices = [
        Index(value = ["is_folded", "normalized_query"], unique = true),
        Index(value = ["is_folded", "updated_at"]),
    ]
)
data class SearchHistoryData(
    @PrimaryKey(autoGenerate = true)
    /** 搜索历史自增主键，用于单条删除；不参与跨设备或跨安装同步。 */
    val id: Long = 0,

    @ColumnInfo(name = "query")
    /** 用户最后一次提交的原始关键词，已去除首尾空白；展示历史项时直接使用该字段。 */
    val query: String,

    @ColumnInfo(name = "normalized_query")
    /** 规范化关键词，规则为 trim + Locale.ROOT lowercase；同一搜索范围内用它去重。 */
    val normalizedQuery: String,

    @ColumnInfo(name = "is_folded")
    /** 搜索范围标记；false 表示普通搜索历史，true 表示折叠搜索历史。 */
    val isFolded: Boolean,

    @ColumnInfo(name = "updated_at")
    /** 最近提交或点击该历史项的时间戳，单位毫秒；用于最近历史排序和超量裁剪。 */
    val updatedAt: Long,
)

/**
 * 搜索历史 DAO。
 *
 * 只负责搜索历史表的轻量读写，不参与剪贴内容搜索；这样历史提示可以独立防抖和裁剪，
 * 不影响已有 Paging 搜索结果流。
 */
@Dao
interface SearchHistoryDao {

    /**
     * 观察当前范围最近历史。
     *
     * 输入为空时使用该查询展示最近历史；limit 由 Repository 固定为业务上限，避免历史面板一次性读取过多行。
     */
    @Query(
        """
        SELECT * FROM search_histories
        WHERE is_folded = :isFolded
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    fun observeRecentHistories(isFolded: Boolean, limit: Int): Flow<List<SearchHistoryData>>

    /**
     * 按关键词模糊观察当前范围历史。
     *
     * `escapedKeyword` 必须由 Repository 预先转义 `%`、`_` 和 `\`，这里通过 `ESCAPE '\'`
     * 把这些符号当普通字符匹配，避免用户输入 `%` 时误命中全部历史。
     */
    @Query(
        """
        SELECT * FROM search_histories
        WHERE is_folded = :isFolded
          AND normalized_query LIKE '%' || :escapedKeyword || '%' ESCAPE '\'
        ORDER BY updated_at DESC
        LIMIT :limit
        """
    )
    fun observeMatchedHistories(isFolded: Boolean, escapedKeyword: String, limit: Int): Flow<List<SearchHistoryData>>

    /**
     * 写入或刷新一条历史。
     *
     * 表级唯一索引会保证同一范围内的规范化关键词只有一条；这里使用 REPLACE 处理重复提交，
     * 更新展示文本和时间并让历史项回到顶部，同时保留用户最后一次输入的大小写或符号写法。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: SearchHistoryData): Long

    /** 删除指定历史；调用方只传数据库主键，删除失败时 Room 返回 0。 */
    @Query("DELETE FROM search_histories WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 清空当前范围所有历史；普通搜索和折叠搜索互不影响。 */
    @Query("DELETE FROM search_histories WHERE is_folded = :isFolded")
    suspend fun clearByScope(isFolded: Boolean): Int

    /**
     * 裁剪当前范围过旧历史。
     *
     * 保存后按更新时间保留最近 limit 条，超出的旧记录立即删除；子查询只读取 id，避免加载完整历史文本。
     */
    @Query(
        """
        DELETE FROM search_histories
        WHERE is_folded = :isFolded
          AND id NOT IN (
              SELECT id FROM search_histories
              WHERE is_folded = :isFolded
              ORDER BY updated_at DESC
              LIMIT :limit
          )
        """
    )
    suspend fun trimScopeToLimit(isFolded: Boolean, limit: Int): Int
}
