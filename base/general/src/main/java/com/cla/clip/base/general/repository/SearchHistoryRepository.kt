package com.cla.clip.base.general.repository

import androidx.room.withTransaction
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.dao.SearchHistoryDao
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.entity.ClipVisibilityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * 搜索历史仓库契约。
 *
 * 搜索历史只服务搜索页的关键词提示和管理，不影响剪贴内容搜索结果；调用方通过 `ClipVisibilityScope`
 * 表达普通搜索或折叠搜索范围，仓库内部转换为数据库布尔字段。
 */
interface SearchHistoryRepository {

    /**
     * 观察当前范围历史。
     *
     * keyword 为空时返回最近历史，非空时返回包含该关键词的历史；符号转义和范围隔离由实现层负责。
     */
    fun observeHistories(scope: ClipVisibilityScope, keyword: String): Flow<List<SearchHistoryData>>

    /**
     * 保存当前范围关键词。
     *
     * 空白关键词和超过长度限制的关键词会被忽略；重复关键词只更新时间和展示文本，不新增重复行。
     */
    suspend fun saveHistory(scope: ClipVisibilityScope, query: String)

    /** 删除单条历史；删除只影响历史提示，不改变当前搜索框和筛选条件。 */
    suspend fun deleteHistory(id: Long): Int

    /** 清空当前范围历史；普通搜索和折叠搜索互不影响。 */
    suspend fun clearHistories(scope: ClipVisibilityScope): Int
}

/**
 * Room 版本的搜索历史仓库。
 *
 * 负责规范化关键词、LIKE 转义、保存后裁剪和范围布尔值转换，让 ViewModel 不需要理解数据库约束。
 */
class SearchHistoryRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val searchHistoryDao: SearchHistoryDao,
) : SearchHistoryRepository {

    companion object {
        /** 每个搜索范围最多保留的历史数量；数量过大容易让提示列表噪声增加。 */
        private const val HISTORY_LIMIT = 50

        /** 可保存关键词最大长度；超长输入通常是误粘贴整段文本，不适合作为历史提示。 */
        private const val MAX_QUERY_LENGTH = 200

        /** LIKE 转义字符；DAO 查询使用同一个字符声明 `ESCAPE`，保证 `%` 和 `_` 被当普通字符。 */
        private const val LIKE_ESCAPE_CHAR = '\\'
    }

    override fun observeHistories(scope: ClipVisibilityScope, keyword: String): Flow<List<SearchHistoryData>> {
        val normalizedKeyword = normalizeQuery(keyword)
        val isFolded = scope.toFoldState()
        return when {
            normalizedKeyword.length > MAX_QUERY_LENGTH -> flowOf(emptyList())
            normalizedKeyword.isBlank() -> searchHistoryDao.observeRecentHistories(isFolded, HISTORY_LIMIT)
            else -> searchHistoryDao.observeMatchedHistories(
                isFolded = isFolded,
                escapedKeyword = normalizedKeyword.escapeForLike(),
                limit = HISTORY_LIMIT
            )
        }
    }

    override suspend fun saveHistory(scope: ClipVisibilityScope, query: String) = withContext(Dispatchers.IO) {
        val displayQuery = query.trim()
        val normalizedQuery = normalizeQuery(displayQuery)
        if (normalizedQuery.isBlank() || displayQuery.length > MAX_QUERY_LENGTH || normalizedQuery.length > MAX_QUERY_LENGTH) {
            return@withContext
        }

        val isFolded = scope.toFoldState()
        val now = System.currentTimeMillis()
        appDatabase.withTransaction {
            searchHistoryDao.upsert(
                SearchHistoryData(
                    query = displayQuery,
                    normalizedQuery = normalizedQuery,
                    isFolded = isFolded,
                    updatedAt = now
                )
            )
            searchHistoryDao.trimScopeToLimit(isFolded, HISTORY_LIMIT)
        }
        AppSetting.markBackupDirty()
        Unit
    }

    override suspend fun deleteHistory(id: Long): Int = withContext(Dispatchers.IO) {
        if (id <= 0L) return@withContext 0
        val deleted = searchHistoryDao.deleteById(id)
        if (deleted > 0) AppSetting.markBackupDirty()
        deleted
    }

    override suspend fun clearHistories(scope: ClipVisibilityScope): Int = withContext(Dispatchers.IO) {
        val deleted = searchHistoryDao.clearByScope(scope.toFoldState())
        if (deleted > 0) AppSetting.markBackupDirty()
        deleted
    }

    /** 将搜索范围转换为数据库字段；集中转换可避免调用方散落布尔语义。 */
    private fun ClipVisibilityScope.toFoldState(): Boolean {
        return when (this) {
            ClipVisibilityScope.VisibleOnly -> false
            ClipVisibilityScope.FoldedOnly -> true
        }
    }

    /** 规范化关键词用于去重和模糊匹配；Locale.ROOT 避免土耳其语等区域大小写规则影响数据库查询。 */
    private fun normalizeQuery(query: String): String {
        return query.trim().lowercase(Locale.ROOT)
    }

    /** 转义 SQL LIKE 通配符和转义符本身，使用户输入的 `%`、`_`、`\` 都只按字面量匹配。 */
    private fun String.escapeForLike(): String {
        return buildString(length) {
            this@escapeForLike.forEach { char ->
                if (char == LIKE_ESCAPE_CHAR || char == '%' || char == '_') {
                    append(LIKE_ESCAPE_CHAR)
                }
                append(char)
            }
        }
    }
}
