package com.cla.clip.feature.magnet.backup

import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.BackupFeatureContributor
import com.cla.clip.base.general.backup.BackupManifest
import com.cla.clip.base.general.backup.BackupPackageBuildSession
import com.cla.clip.base.general.backup.BackupPackageFile
import com.cla.clip.base.general.backup.BackupPackageReader
import com.cla.clip.base.general.backup.BackupPackageRef
import com.cla.clip.base.general.backup.BackupRestoreCategoryReport
import com.cla.clip.base.general.backup.RestoreCounter
import com.cla.clip.feature.magnet.data.MagnetDao
import com.cla.clip.feature.magnet.data.MagnetDownloadRecordData
import com.cla.clip.feature.magnet.data.MagnetSearchHistoryData
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** 磁力模块备份参与者；模块未编译进应用时宿主不会导出、统计或恢复这些 entry。 */
@Singleton
class MagnetBackupContributorImpl @Inject constructor(
    private val magnetDao: MagnetDao,
    private val packageReader: BackupPackageReader,
) : BackupFeatureContributor {
    override val contributorId: String = "magnet"

    override suspend fun readHighWaterMarks(): Map<String, Long> {
        return mapOf(
            SEARCH_HISTORY_MAX_ID_KEY to magnetDao.maxSearchHistoryIdForBackup(),
            DOWNLOAD_RECORD_MAX_ID_KEY to magnetDao.maxDownloadRecordIdForBackup()
        )
    }

    override suspend fun exportJsonl(
        session: BackupPackageBuildSession,
        highWaterMarks: Map<String, Long>,
        featureCounts: MutableMap<String, Int>,
    ): List<BackupPackageFile> {
        val files = mutableListOf<BackupPackageFile>()
        files += exportSearchHistoryLines(session, highWaterMarks[SEARCH_HISTORY_MAX_ID_KEY] ?: 0L, featureCounts)
        files += exportDownloadRecordLines(session, highWaterMarks[DOWNLOAD_RECORD_MAX_ID_KEY] ?: 0L, featureCounts)
        return files
    }

    override suspend fun restoreJsonl(
        ref: BackupPackageRef,
        manifest: BackupManifest,
    ): List<BackupRestoreCategoryReport> {
        val historyRestore = if (ref.hasEntry(MAGNET_SEARCH_HISTORIES_JSONL_PATH)) {
            restoreJsonlSearchHistories(ref)
        } else {
            RestoreCounter()
        }
        val recordRestore = if (ref.hasEntry(MAGNET_DOWNLOAD_RECORDS_JSONL_PATH)) {
            restoreJsonlDownloadRecords(ref)
        } else {
            RestoreCounter()
        }
        return listOf(
            BackupRestoreCategoryReport(MAGNET_SEARCH_HISTORIES_CATEGORY, historyRestore.inserted, historyRestore.updated, historyRestore.skipped),
            BackupRestoreCategoryReport(MAGNET_DOWNLOAD_RECORDS_CATEGORY, recordRestore.inserted, recordRestore.updated, recordRestore.skipped),
        )
    }

    private suspend fun exportSearchHistoryLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        featureCounts: MutableMap<String, Int>,
    ): BackupPackageFile {
        return session.writeJsonLines(MAGNET_SEARCH_HISTORIES_JSONL_PATH, BackupMagnetSearchHistory.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = magnetDao.loadSearchHistoriesPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupMagnetSearchHistory())
                    featureCounts[MAGNET_SEARCH_HISTORY_COUNT_KEY] = featureCounts.getOrDefault(MAGNET_SEARCH_HISTORY_COUNT_KEY, 0) + 1
                }
                lastId = page.last().id
            }
        }
    }

    private suspend fun exportDownloadRecordLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        featureCounts: MutableMap<String, Int>,
    ): BackupPackageFile {
        return session.writeJsonLines(MAGNET_DOWNLOAD_RECORDS_JSONL_PATH, BackupMagnetDownloadRecord.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = magnetDao.loadDownloadRecordsPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupMagnetDownloadRecord())
                    featureCounts[MAGNET_DOWNLOAD_RECORD_COUNT_KEY] = featureCounts.getOrDefault(MAGNET_DOWNLOAD_RECORD_COUNT_KEY, 0) + 1
                }
                lastId = page.last().id
            }
        }
    }

    private suspend fun restoreJsonlSearchHistories(ref: BackupPackageRef): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<MagnetSearchHistoryData>()
        packageReader.readJsonLines(ref, MAGNET_SEARCH_HISTORIES_JSONL_PATH, BackupMagnetSearchHistory.serializer()) { item ->
            item.toEntity()?.let { chunk += it }
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreSearchHistoryEntities(chunk.toList())
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreSearchHistoryEntities(chunk.toList())
        return counter
    }

    private suspend fun restoreSearchHistoryEntities(entities: List<MagnetSearchHistoryData>): RestoreCounter {
        if (entities.isEmpty()) return RestoreCounter()
        var counter = RestoreCounter()
        entities.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
            val existing = magnetDao
                .loadSearchHistoriesByQueriesForBackup(chunk.map { it.normalizedQuery })
                .associateBy { it.normalizedQuery }
            val toWrite = mutableListOf<MagnetSearchHistoryData>()
            var inserted = 0
            var updated = 0
            var skipped = 0
            chunk.forEach { entity ->
                val local = existing[entity.normalizedQuery]
                when {
                    local == null -> {
                        inserted++
                        toWrite += entity.copy(id = 0)
                    }
                    local.sameBackupContent(entity) -> skipped++
                    else -> {
                        updated++
                        toWrite += entity.copy(id = local.id)
                    }
                }
            }
            if (toWrite.isNotEmpty()) magnetDao.upsertSearchHistoriesForBackup(toWrite)
            counter += RestoreCounter(inserted, updated, skipped)
        }
        return counter
    }

    private suspend fun restoreJsonlDownloadRecords(ref: BackupPackageRef): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<MagnetDownloadRecordData>()
        var skippedInvalid = 0
        packageReader.readJsonLines(ref, MAGNET_DOWNLOAD_RECORDS_JSONL_PATH, BackupMagnetDownloadRecord.serializer()) { item ->
            val entity = item.toEntity()
            if (entity == null) {
                skippedInvalid += 1
            } else {
                chunk += entity
            }
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreDownloadRecordEntities(chunk.toList())
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreDownloadRecordEntities(chunk.toList())
        return counter + RestoreCounter(skipped = skippedInvalid)
    }

    private suspend fun restoreDownloadRecordEntities(entities: List<MagnetDownloadRecordData>): RestoreCounter {
        if (entities.isEmpty()) return RestoreCounter()
        var counter = RestoreCounter()
        entities.groupBy { it.sourceId }.forEach { (sourceId, scopedEntities) ->
            scopedEntities.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
                val existing = magnetDao
                    .loadDownloadRecordsBySourceAndHashesForBackup(sourceId, chunk.map { it.infoHash })
                    .associateBy { it.infoHash }
                val toWrite = mutableListOf<MagnetDownloadRecordData>()
                var inserted = 0
                var updated = 0
                var skipped = 0
                chunk.forEach { entity ->
                    val local = existing[entity.infoHash]
                    when {
                        local == null -> {
                            inserted++
                            toWrite += entity.copy(id = 0)
                        }
                        local.sameBackupContent(entity) -> skipped++
                        else -> {
                            val merged = mergeMagnetDownloadRecord(local, entity)
                            if (local.sameBackupContent(merged)) {
                                skipped++
                            } else {
                                updated++
                                toWrite += merged
                            }
                        }
                    }
                }
                if (toWrite.isNotEmpty()) magnetDao.upsertDownloadRecordsForBackup(toWrite)
                counter += RestoreCounter(inserted, updated, skipped)
            }
        }
        return counter
    }

    private fun mergeMagnetDownloadRecord(
        local: MagnetDownloadRecordData,
        backup: MagnetDownloadRecordData,
    ): MagnetDownloadRecordData {
        val newer = if (backup.lastUsedAt > local.lastUsedAt) backup else local
        return newer.copy(
            id = local.id,
            firstUsedAt = minOf(local.firstUsedAt, backup.firstUsedAt),
            lastUsedAt = maxOf(local.lastUsedAt, backup.lastUsedAt),
            lastSourceQuery = newer.lastSourceQuery
        )
    }

    private fun BackupPackageRef.hasEntry(path: String): Boolean {
        return ZipFile(requireReadable()).use { zip -> zip.getEntry(path) != null }
    }

    private companion object {
        private const val EXPORT_PAGE_SIZE = 500
        private const val WRITE_CHUNK_SIZE = 300
        private const val SEARCH_HISTORY_MAX_ID_KEY = "magnet_search_history_max_id"
        private const val DOWNLOAD_RECORD_MAX_ID_KEY = "magnet_download_record_max_id"
    }
}
