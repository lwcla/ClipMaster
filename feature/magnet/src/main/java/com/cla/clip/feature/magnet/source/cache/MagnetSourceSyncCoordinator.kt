package com.cla.clip.feature.magnet.source.cache

import android.content.Context
import androidx.room.withTransaction
import com.cla.clip.feature.magnet.MAGNET_SOURCE_ACADEMIC_TORRENTS
import com.cla.clip.feature.magnet.MagnetInfoHashNormalizer
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 磁力源索引同步协调器，保证同一时间只运行一个同步任务。 */
@Singleton
class MagnetSourceSyncCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: MagnetSourceCacheDatabase,
    private val dao: MagnetSourceCacheDao,
    private val source: AcademicTorrentsSource,
) {
    companion object {
        private const val TAG = "MagnetSourceSyncCoordinator"
        private const val IMPORT_BATCH_SIZE = 500
        private const val SYNC_COOLDOWN_MS = 5L * 60L * 1000L
        private const val TEMP_DIR_NAME = "magnet_source_sync"
    }

    private val mutex = Mutex()
    private val _progress = MutableStateFlow(
        MagnetSourceSyncProgress(
            taskId = "",
            sourceId = MAGNET_SOURCE_ACADEMIC_TORRENTS
        )
    )

    /** 页面观察的低敏同步进度。 */
    val progress: StateFlow<MagnetSourceSyncProgress> = _progress

    /** 读取当前源缓存状态。 */
    suspend fun getCacheState(): MagnetSourceCacheState = withContext(Dispatchers.IO) {
        val meta = dao.getMeta(MAGNET_SOURCE_ACADEMIC_TORRENTS)
        val count = dao.countItems(MAGNET_SOURCE_ACADEMIC_TORRENTS)
        when {
            meta == null && count == 0 -> MagnetSourceCacheState(reason = MagnetSourceStatusReason.NotSynced)
            count == 0 -> MagnetSourceCacheState(
                reason = if (meta?.complete == true) MagnetSourceStatusReason.EmptyIndex else MagnetSourceStatusReason.CacheCleared,
                itemCount = 0,
                syncCompletedAt = meta?.syncCompletedAt ?: 0,
                cacheSizeBytes = meta?.cacheSizeBytes ?: 0,
            )
            meta?.complete == true -> MagnetSourceCacheState(
                reason = MagnetSourceStatusReason.Ready,
                itemCount = count,
                syncCompletedAt = meta.syncCompletedAt,
                cacheSizeBytes = meta.cacheSizeBytes,
            )
            else -> MagnetSourceCacheState(
                reason = MagnetSourceStatusReason.CacheCleared,
                itemCount = count,
                syncCompletedAt = meta?.syncCompletedAt ?: 0,
                cacheSizeBytes = meta?.cacheSizeBytes ?: 0,
            )
        }
    }

    /** 同步 Academic Torrents 索引，保留旧索引直到新索引解析成功。 */
    suspend fun sync(force: Boolean = false): MagnetSourceCacheState {
        return mutex.withLock {
            val sourceId = MAGNET_SOURCE_ACADEMIC_TORRENTS
            val taskId = "magnet-${System.currentTimeMillis().toString(36)}"
            val oldMeta = dao.getMeta(sourceId)
            val now = System.currentTimeMillis()
            if (!force && oldMeta?.complete == true && now - oldMeta.syncCompletedAt in 0 until SYNC_COOLDOWN_MS) {
                _progress.value = MagnetSourceSyncProgress(
                    taskId = taskId,
                    sourceId = sourceId,
                    phase = MagnetSourceSyncPhase.Completed,
                    reason = MagnetSourceStatusReason.Cooldown,
                    importedCount = oldMeta.itemCount
                )
                return@withLock getCacheState().copy(reason = MagnetSourceStatusReason.Cooldown)
            }

            val tempDir = File(context.cacheDir, TEMP_DIR_NAME).apply {
                deleteRecursively()
                mkdirs()
            }
            val xmlFile = File(tempDir, "academic_torrents_database.xml")
            _progress.value = MagnetSourceSyncProgress(taskId = taskId, sourceId = sourceId, phase = MagnetSourceSyncPhase.Checking)
            logD(TAG) { "磁力源同步开始 taskId=$taskId sourceId=$sourceId force=$force" }
            try {
                val startedMeta = oldMeta?.copy(syncStartedAt = now, lastFailureReason = null)
                    ?: MagnetSourceCacheMetaData(sourceId = sourceId, syncStartedAt = now)
                dao.upsertMeta(startedMeta)
                _progress.value = _progress.value.copy(phase = MagnetSourceSyncPhase.Downloading)
                when (val result = source.downloadDatabaseXml(xmlFile, oldMeta?.etag, oldMeta?.lastModified)) {
                    is AcademicTorrentsDownloadResult.NotModified -> {
                        val currentCount = dao.countItems(sourceId)
                        val completed = oldMeta?.copy(
                            etag = result.etag ?: oldMeta.etag,
                            lastModified = result.lastModified ?: oldMeta.lastModified,
                            syncStartedAt = now,
                            lastFailureReason = null,
                            complete = currentCount > 0,
                        ) ?: MagnetSourceCacheMetaData(sourceId = sourceId, syncStartedAt = now, itemCount = currentCount, complete = currentCount > 0)
                        dao.upsertMeta(completed)
                        _progress.value = _progress.value.copy(
                            phase = MagnetSourceSyncPhase.Completed,
                            importedCount = currentCount,
                            reason = MagnetSourceStatusReason.NotModified
                        )
                        return@withLock getCacheState().copy(reason = MagnetSourceStatusReason.NotModified)
                    }
                    is AcademicTorrentsDownloadResult.Failed -> {
                        markFailure(oldMeta, sourceId, now, result.reason)
                        return@withLock getCacheState().copy(reason = result.reason)
                    }
                    is AcademicTorrentsDownloadResult.Downloaded -> {
                        _progress.value = _progress.value.copy(phase = MagnetSourceSyncPhase.Parsing)
                        val imported = importDownloadedXml(
                            taskId = taskId,
                            sourceId = sourceId,
                            xmlFile = result.file,
                            completedAt = now,
                            etag = result.etag,
                            lastModified = result.lastModified,
                            cacheSizeBytes = result.file.length().coerceAtLeast(result.contentLength)
                        )
                        _progress.value = _progress.value.copy(
                            phase = MagnetSourceSyncPhase.Completed,
                            importedCount = imported,
                            reason = if (imported > 0) MagnetSourceStatusReason.Ready else MagnetSourceStatusReason.EmptyIndex
                        )
                        logD(TAG) { "磁力源同步完成 taskId=$taskId sourceId=$sourceId imported=$imported" }
                        return@withLock getCacheState()
                    }
                }
            } catch (cancelled: CancellationException) {
                markFailure(oldMeta, sourceId, now, MagnetSourceStatusReason.UserCancelled)
                _progress.value = _progress.value.copy(phase = MagnetSourceSyncPhase.Cancelled, reason = MagnetSourceStatusReason.UserCancelled)
                throw cancelled
            } catch (throwable: Throwable) {
                logW(TAG, throwable) { "磁力源同步失败 taskId=$taskId sourceId=$sourceId reasonCode=parse_failed" }
                markFailure(oldMeta, sourceId, now, MagnetSourceStatusReason.ParseFailed)
                _progress.value = _progress.value.copy(phase = MagnetSourceSyncPhase.Failed, reason = MagnetSourceStatusReason.ParseFailed)
                return@withLock getCacheState().copy(reason = MagnetSourceStatusReason.ParseFailed)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private suspend fun importDownloadedXml(
        taskId: String,
        sourceId: String,
        xmlFile: File,
        completedAt: Long,
        etag: String?,
        lastModified: String?,
        cacheSizeBytes: Long,
    ): Int {
        val parsed = mutableListOf<MagnetSourceItemData>()
        var parsedCount = 0
        source.parseDatabaseXml(xmlFile) { entry ->
            parsedCount += 1
            val normalizedHash = MagnetInfoHashNormalizer.normalize(entry.infoHash)
            if (normalizedHash != null) {
                parsed += entry.toCacheItem(sourceId, normalizedHash, completedAt)
            }
            if (parsed.size >= IMPORT_BATCH_SIZE) {
                _progress.value = MagnetSourceSyncProgress(
                    taskId = taskId,
                    sourceId = sourceId,
                    parsedCount = parsedCount,
                    importedCount = parsed.size,
                    phase = MagnetSourceSyncPhase.Parsing
                )
            }
        }
        val finalItems = parsed.distinctBy { it.sourceId to it.infoHash }
        val finalCount = finalItems.size
        var writtenCount = 0
        database.withTransaction {
            val meta = MagnetSourceCacheMetaData(
                sourceId = sourceId,
                etag = etag,
                lastModified = lastModified,
                syncStartedAt = completedAt,
                syncCompletedAt = System.currentTimeMillis(),
                itemCount = finalCount,
                cacheSizeBytes = cacheSizeBytes.coerceAtLeast(0),
                lastFailureReason = null,
                complete = finalCount > 0
            )
            if (finalCount > 0) {
                // 只有完整解析完成后才替换索引；解析或下载失败不会走到这里，旧索引可保留。
                dao.clearItems(sourceId)
                finalItems.chunked(IMPORT_BATCH_SIZE).forEach { chunk ->
                    dao.insertItems(chunk)
                    writtenCount += chunk.size
                    _progress.value = MagnetSourceSyncProgress(
                        taskId = taskId,
                        sourceId = sourceId,
                        parsedCount = parsedCount,
                        importedCount = writtenCount.coerceAtMost(finalCount),
                        phase = MagnetSourceSyncPhase.Importing
                    )
                }
            }
            dao.upsertMeta(meta)
        }
        return finalCount
    }

    private suspend fun markFailure(
        oldMeta: MagnetSourceCacheMetaData?,
        sourceId: String,
        startedAt: Long,
        reason: MagnetSourceStatusReason,
    ) {
        val currentCount = dao.countItems(sourceId)
        dao.upsertMeta(
            (oldMeta ?: MagnetSourceCacheMetaData(sourceId = sourceId)).copy(
                syncStartedAt = startedAt,
                itemCount = currentCount,
                lastFailureReason = reason.code,
                complete = currentCount > 0 && oldMeta?.complete == true
            )
        )
    }

    private fun AcademicTorrentsEntry.toCacheItem(
        sourceId: String,
        normalizedHash: String,
        updatedAt: Long,
    ): MagnetSourceItemData {
        val searchText = buildString {
            append(title)
            append(' ')
            append(category.orEmpty())
            append(' ')
            append(description.orEmpty())
            append(' ')
            append(normalizedHash)
        }
        return MagnetSourceItemData(
            sourceId = sourceId,
            infoHash = normalizedHash,
            title = title,
            detailUrl = detailUrl,
            sizeBytes = sizeBytes,
            category = category,
            description = description,
            updatedAt = updatedAt,
            searchText = searchText
        )
    }
}
