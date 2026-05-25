package com.cla.clip.feature.magnet.data

import androidx.room.withTransaction
import androidx.paging.PagingSource
import com.cla.clip.feature.magnet.MAGNET_SOURCE_ACADEMIC_TORRENTS
import com.cla.clip.feature.magnet.MagnetInfoHashNormalizer
import com.cla.clip.feature.magnet.MagnetSourceProvider
import com.cla.clip.feature.magnet.MagnetTextNormalizer
import com.cla.clip.feature.magnet.MagnetUriBuilder
import com.cla.clip.feature.magnet.api.MagnetDirtyNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁力用户数据仓库。
 *
 * 这里只管理搜索历史和复制/打开记录，不下载源索引、不执行本地搜索；源索引属于 App 缓存库职责，
 * 和主库用户数据保持边界清晰。
 */
@Singleton
class MagnetRepository @Inject constructor(
    private val database: MagnetFeatureDatabase,
    private val magnetDao: MagnetDao,
    private val dirtyNotifier: MagnetDirtyNotifier,
) {
    companion object {
        /** 历史提示最多显示的条数。 */
        private const val HISTORY_LIMIT = 50
    }

    /** 观察磁力搜索历史。 */
    fun observeSearchHistories(keyword: String): Flow<List<MagnetSearchHistoryData>> {
        val normalizedKeyword = MagnetTextNormalizer.normalizeKey(keyword)
        return when {
            normalizedKeyword.length > MagnetTextNormalizer.MAX_QUERY_LENGTH -> flowOf(emptyList())
            normalizedKeyword.isBlank() -> magnetDao.observeRecentHistories(HISTORY_LIMIT)
            else -> magnetDao.observeMatchedHistories(
                escapedKeyword = MagnetTextNormalizer.escapeForLike(normalizedKeyword),
                limit = HISTORY_LIMIT
            )
        }
    }

    /** 只在用户提交合法搜索时保存历史。 */
    suspend fun saveSearchHistory(query: String) = withContext(Dispatchers.IO) {
        val displayQuery = MagnetTextNormalizer.normalizeDisplayQuery(query)
        val normalizedQuery = MagnetTextNormalizer.normalizeKey(displayQuery)
        if (displayQuery.isBlank() || normalizedQuery.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        database.withTransaction {
            magnetDao.upsertSearchHistory(
                MagnetSearchHistoryData(
                    query = displayQuery,
                    normalizedQuery = normalizedQuery,
                    updatedAt = now
                )
            )
            magnetDao.trimSearchHistoriesToLimit(HISTORY_LIMIT)
        }
        dirtyNotifier.markDirtyAndSchedule()
    }

    /** 删除单条磁力搜索历史。 */
    suspend fun deleteSearchHistory(id: Long): Int = withContext(Dispatchers.IO) {
        if (id <= 0L) return@withContext 0
        val deleted = magnetDao.deleteSearchHistory(id)
        if (deleted > 0) dirtyNotifier.markDirtyAndSchedule()
        deleted
    }

    /** 清空全部磁力搜索历史，不影响下载记录。 */
    suspend fun clearSearchHistories(): Int = withContext(Dispatchers.IO) {
        val deleted = magnetDao.clearSearchHistories()
        if (deleted > 0) dirtyNotifier.markDirtyAndSchedule()
        deleted
    }

    /** 分页加载磁力下载记录。 */
    fun pagingDownloadRecords(): PagingSource<Int, MagnetDownloadRecordData> {
        return magnetDao.pagingDownloadRecords()
    }

    /** 观察磁力下载记录总数。 */
    fun observeDownloadRecordCount(): Flow<Int> {
        return magnetDao.observeDownloadRecordCount()
    }

    /** 当前排序下全部磁力下载记录 id。 */
    suspend fun getDownloadRecordIds(): List<Long> = withContext(Dispatchers.IO) {
        magnetDao.getDownloadRecordIds()
    }

    /** 按 id 读取磁力记录。 */
    suspend fun getDownloadRecord(id: Long): MagnetDownloadRecordData? = withContext(Dispatchers.IO) {
        magnetDao.getDownloadRecord(id)
    }

    /** 批量删除磁力记录，不删除搜索历史或任何本地文件。 */
    suspend fun deleteDownloadRecords(ids: Set<Long>): Int = withContext(Dispatchers.IO) {
        val normalizedIds = ids.filter { it > 0L }.toSet()
        if (normalizedIds.isEmpty()) return@withContext 0
        val deleted = magnetDao.deleteDownloadRecords(normalizedIds)
        if (deleted > 0) dirtyNotifier.markDirtyAndSchedule()
        deleted
    }

    /** 清空磁力记录，不影响搜索历史、视频下载、图片下载或本地文件。 */
    suspend fun clearDownloadRecords(): Int = withContext(Dispatchers.IO) {
        val deleted = magnetDao.clearDownloadRecords()
        if (deleted > 0) dirtyNotifier.markDirtyAndSchedule()
        deleted
    }

    /**
     * 复制或打开搜索结果时写入/刷新磁力记录。
     *
     * 同一 `sourceId + infoHash` 只保留一条记录，`firstUsedAt` 保留最早值，`lastUsedAt` 按本次动作刷新。
     */
    suspend fun recordMagnetUse(
        sourceId: String = MAGNET_SOURCE_ACADEMIC_TORRENTS,
        infoHash: String,
        title: String,
        detailUrl: String?,
        sizeBytes: Long?,
        category: String?,
        magnetUri: String?,
        sourceQuery: String?,
        now: Long = System.currentTimeMillis(),
    ): MagnetDownloadRecordData? = withContext(Dispatchers.IO) {
        if (!MagnetSourceProvider.isAllowed(sourceId)) return@withContext null
        val normalizedHash = MagnetInfoHashNormalizer.normalize(infoHash) ?: return@withContext null
        val safeTitle = title.trim().ifBlank { normalizedHash }
        val safeMagnetUri = magnetUri
            ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: MagnetUriBuilder.build(normalizedHash, safeTitle)
            ?: return@withContext null
        val safeQuery = sourceQuery
            ?.let(MagnetTextNormalizer::normalizeDisplayQuery)
            ?.takeIf { it.isNotBlank() }
        val existing = magnetDao.getDownloadRecordByKey(sourceId, normalizedHash)
        val record = if (existing == null) {
            MagnetDownloadRecordData(
                sourceId = sourceId,
                infoHash = normalizedHash,
                title = safeTitle,
                detailUrl = detailUrl?.trim()?.takeIf { it.isNotBlank() },
                sizeBytes = sizeBytes?.takeIf { it >= 0L },
                category = category?.trim()?.takeIf { it.isNotBlank() },
                magnetUri = safeMagnetUri,
                lastSourceQuery = safeQuery,
                firstUsedAt = now,
                lastUsedAt = now
            )
        } else {
            existing.copy(
                title = safeTitle,
                detailUrl = detailUrl?.trim()?.takeIf { it.isNotBlank() } ?: existing.detailUrl,
                sizeBytes = sizeBytes?.takeIf { it >= 0L } ?: existing.sizeBytes,
                category = category?.trim()?.takeIf { it.isNotBlank() } ?: existing.category,
                magnetUri = safeMagnetUri,
                lastSourceQuery = safeQuery ?: existing.lastSourceQuery,
                firstUsedAt = minOf(existing.firstUsedAt, now),
                lastUsedAt = now
            )
        }
        magnetDao.upsertDownloadRecord(record)
        dirtyNotifier.markDirtyAndSchedule()
        record
    }

    /** 点击下载记录时只刷新最近使用时间和最近来源搜索词。 */
    suspend fun touchDownloadRecord(
        id: Long,
        sourceQuery: String? = null,
        now: Long = System.currentTimeMillis(),
    ): MagnetDownloadRecordData? = withContext(Dispatchers.IO) {
        val existing = magnetDao.getDownloadRecord(id) ?: return@withContext null
        val rebuiltMagnet = existing.magnetUri.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: MagnetUriBuilder.build(existing.infoHash, existing.title)
            ?: return@withContext null
        val safeQuery = sourceQuery
            ?.let(MagnetTextNormalizer::normalizeDisplayQuery)
            ?.takeIf { it.isNotBlank() }
        val updated = existing.copy(
            magnetUri = rebuiltMagnet,
            lastSourceQuery = safeQuery ?: existing.lastSourceQuery,
            firstUsedAt = minOf(existing.firstUsedAt, now),
            lastUsedAt = now
        )
        magnetDao.upsertDownloadRecord(updated)
        dirtyNotifier.markDirtyAndSchedule()
        updated
    }
}
