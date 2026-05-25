package com.cla.clip.base.general.backup

import androidx.room.withTransaction
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.utils.logD
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份快照恢复器。
 *
 * Restorer 只负责已校验备份包的事务恢复、幂等合并和分类报告；解析校验、任务互斥、UI/WebDAV 文件来源仍由仓库或页面层
 * 编排，避免 `BackupRepository` 继续承载每张表的恢复细节。
 */
@Singleton
class BackupSnapshotRestorer @Inject constructor(
    /** 应用 Room 数据库，用于事务性恢复所有可备份表。 */
    private val appDatabase: AppDatabase,
    /** 文件型备份包读取器，负责 JSONL entry 读取。 */
    private val packageReader: BackupPackageReader,
    /** 可选功能恢复参与者；未编译对应模块时集合为空。 */
    private val featureContributors: Set<@JvmSuppressWildcards BackupFeatureContributor>,
) {
    companion object {
        /** 日志标签，只记录恢复数量和时间归一化摘要，不输出备份内容。 */
        private const val TAG = "BackupSnapshotRestorer"

        /** 分块写入大小，避免一次性向 Room 绑定过多实体造成内存和 SQL 参数压力。 */
        private const val WRITE_CHUNK_SIZE = 300
    }

    /** 在 Room transaction 内恢复旧 v1 内存快照。 */
    internal suspend fun restoreSnapshot(
        snapshot: BackupSnapshot,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): BackupRestoreReport {
        return appDatabase.withTransaction {
            restoreSnapshotInTransaction(snapshot, timeNormalizer)
        }
    }

    /** 在 Room transaction 内恢复 v2 JSONL 文件型备份。 */
    internal suspend fun restorePackage(
        ref: BackupPackageRef,
        manifest: BackupManifest,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): BackupRestoreReport {
        return appDatabase.withTransaction {
            restorePackageInTransaction(ref, manifest, timeNormalizer)
        }
    }

    /** 在事务内执行实际恢复，调用方必须已经持有 Room transaction。 */
    private suspend fun restoreSnapshotInTransaction(
        snapshot: BackupSnapshot,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): BackupRestoreReport {
        var inserted = 0
        var updated = 0
        var skipped = 0

        val sourceAppRestore = restoreSourceAppEntities(snapshot.data.sourceApps.map { it.toEntity() })
        inserted += sourceAppRestore.inserted
        updated += sourceAppRestore.updated
        skipped += sourceAppRestore.skipped

        val linkPreviewRestore = restoreLinkPreviewEntities(snapshot.data.linkPreviews.map { it.toEntity() })
        inserted += linkPreviewRestore.inserted
        updated += linkPreviewRestore.updated
        skipped += linkPreviewRestore.skipped

        val clipRestore = restoreClips(snapshot, timeNormalizer)
        inserted += clipRestore.inserted
        updated += clipRestore.updated
        skipped += clipRestore.skipped

        val historyRestore = restoreSearchHistoryEntities(snapshot.data.searchHistories.map { it.toEntity() })
        inserted += historyRestore.inserted
        updated += historyRestore.updated
        skipped += historyRestore.skipped

        restoreSettings(snapshot.data.settings)

        val videoRestore = restoreVideoDownloads(snapshot)
        inserted += videoRestore.inserted
        updated += videoRestore.updated
        skipped += videoRestore.skipped

        val imageBatchRestore = restoreImageBatches(snapshot)
        inserted += imageBatchRestore.inserted
        updated += imageBatchRestore.updated
        skipped += imageBatchRestore.skipped

        val imageItemRestore = restoreImageItems(snapshot)
        inserted += imageItemRestore.inserted
        updated += imageItemRestore.updated
        skipped += imageItemRestore.skipped

        logD(TAG) {
            "备份恢复写库完成 inserted=$inserted updated=$updated skipped=$skipped " +
                "futureTimeNormalized=${timeNormalizer.normalizedFieldCount} clockSkewMs=${timeNormalizer.clockSkewMillis}"
        }
        return BackupRestoreReport(
            insertedCount = inserted,
            updatedCount = updated,
            skippedCount = skipped,
            categoryReports = listOf(
                BackupRestoreCategoryReport(BackupProgressCategory.SourceApps, sourceAppRestore.inserted, sourceAppRestore.updated, sourceAppRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.LinkPreviews, linkPreviewRestore.inserted, linkPreviewRestore.updated, linkPreviewRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.Clips, clipRestore.inserted, clipRestore.updated, clipRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.SearchHistories, historyRestore.inserted, historyRestore.updated, historyRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.VideoDownloads, videoRestore.inserted, videoRestore.updated, videoRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.ImageBatches, imageBatchRestore.inserted, imageBatchRestore.updated, imageBatchRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.ImageItems, imageItemRestore.inserted, imageItemRestore.updated, imageItemRestore.skipped)
            )
        )
    }

    /** 在事务内执行 v2 文件型备份恢复，调用方必须已经完成 manifest/checksum 校验。 */
    private suspend fun restorePackageInTransaction(
        ref: BackupPackageRef,
        manifest: BackupManifest,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): BackupRestoreReport {
        val sourceApps = mutableListOf<BackupSourceApp>()
        packageReader.readJsonLines(ref, SOURCE_APPS_JSONL_PATH, BackupSourceApp.serializer()) { sourceApps += it }
        val sourceAppRestore = restoreSourceAppEntities(sourceApps.map { it.toEntity() })

        val linkPreviews = mutableListOf<BackupLinkPreview>()
        packageReader.readJsonLines(ref, LINK_PREVIEWS_JSONL_PATH, BackupLinkPreview.serializer()) { linkPreviews += it }
        val linkPreviewRestore = restoreLinkPreviewEntities(linkPreviews.map { it.toEntity() })

        val sourceAppsByPackage = sourceApps.associateBy { it.packageName }
        val linkPreviewsByLink = linkPreviews.associateBy { it.link }
        val clipRestore = restoreJsonlClips(ref, manifest, sourceAppsByPackage, linkPreviewsByLink, timeNormalizer)
        val historyRestore = restoreJsonlSearchHistories(ref)
        restoreSettings(BackupJson.decodeSettings(packageReader.readText(ref, SETTINGS_PATH)))
        val videoRestore = restoreJsonlVideoDownloads(ref, manifest)
        val imageBatchRestore = restoreJsonlImageBatches(ref, manifest)
        val imageItemRestore = restoreJsonlImageItems(ref)
        val featureReports = featureContributors
            .sortedBy { it.contributorId }
            .flatMap { contributor -> contributor.restoreJsonl(ref, manifest) }
        val reports = listOf(
            BackupRestoreCategoryReport(BackupProgressCategory.SourceApps, sourceAppRestore.inserted, sourceAppRestore.updated, sourceAppRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.LinkPreviews, linkPreviewRestore.inserted, linkPreviewRestore.updated, linkPreviewRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.Clips, clipRestore.inserted, clipRestore.updated, clipRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.SearchHistories, historyRestore.inserted, historyRestore.updated, historyRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.VideoDownloads, videoRestore.inserted, videoRestore.updated, videoRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.ImageBatches, imageBatchRestore.inserted, imageBatchRestore.updated, imageBatchRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.ImageItems, imageItemRestore.inserted, imageItemRestore.updated, imageItemRestore.skipped)
        ) + featureReports
        val inserted = reports.sumOf { it.insertedCount }
        val updated = reports.sumOf { it.updatedCount }
        val skipped = reports.sumOf { it.skippedCount }
        logD(TAG) {
            "备份恢复写库完成 inserted=$inserted updated=$updated skipped=$skipped schema=${manifest.schemaVersion} " +
                "futureTimeNormalized=${timeNormalizer.normalizedFieldCount} clockSkewMs=${timeNormalizer.clockSkewMillis}"
        }
        return BackupRestoreReport(
            insertedCount = inserted,
            updatedCount = updated,
            skippedCount = skipped,
            categoryReports = reports
        )
    }

    /** 从 JSONL 恢复剪贴记录，按 chunk 查询已有记录，避免一次性加载全部 id。 */
    private suspend fun restoreJsonlClips(
        ref: BackupPackageRef,
        manifest: BackupManifest,
        sourceApps: Map<String, BackupSourceApp>,
        linkPreviews: Map<String, BackupLinkPreview>,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<BackupClip>()
        packageReader.readJsonLines(ref, CLIPS_JSONL_PATH, BackupClip.serializer()) { item ->
            chunk += item
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreClipChunk(chunk, manifest, sourceApps, linkPreviews, timeNormalizer)
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreClipChunk(chunk, manifest, sourceApps, linkPreviews, timeNormalizer)
        return counter
    }

    /** 恢复剪贴 chunk，并保护本地较新的用户状态。 */
    private suspend fun restoreClipChunk(
        backups: List<BackupClip>,
        manifest: BackupManifest,
        sourceApps: Map<String, BackupSourceApp>,
        linkPreviews: Map<String, BackupLinkPreview>,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): RestoreCounter {
        val existing = appDatabase.clipDao().loadClipsByIdsForBackup(backups.map { it.id }).associateBy { it.id }
        val toWrite = mutableListOf<ClipData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        backups.forEach { backup ->
            val entity = backup.toEntity(
                sourceApp = backup.sourceAppPackage?.let { sourceApps[it] },
                linkPreview = backup.link?.let { linkPreviews[it] },
                timeNormalizer = timeNormalizer
            )
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }
                local.lastUserStateTime() > timeNormalizer.normalizedManifestCreatedAt ||
                    local.lastUserStateTime() >= entity.lastUserStateTime() -> skipped++
                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        if (toWrite.isNotEmpty()) appDatabase.clipDao().upsertClipsForBackup(toWrite)
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 恢复来源 App 缓存，按包名生成幂等报告。 */
    private suspend fun restoreSourceAppEntities(entities: List<SourceAppData>): RestoreCounter {
        if (entities.isEmpty()) return RestoreCounter()
        var counter = RestoreCounter()
        entities.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
            val existing = appDatabase.sourceAppDao()
                .loadByPackageNamesForBackup(chunk.map { it.packageName })
                .associateBy { it.packageName }
            val toWrite = mutableListOf<SourceAppData>()
            var inserted = 0
            var updated = 0
            var skipped = 0
            chunk.forEach { entity ->
                val local = existing[entity.packageName]
                when {
                    local == null -> {
                        inserted++
                        toWrite += entity
                    }
                    local.sameBackupContent(entity) -> skipped++
                    else -> {
                        updated++
                        toWrite += entity
                    }
                }
            }
            if (toWrite.isNotEmpty()) appDatabase.sourceAppDao().upsertAllForBackup(toWrite)
            counter += RestoreCounter(inserted, updated, skipped)
        }
        return counter
    }

    /** 恢复链接预览缓存，按链接主键生成幂等报告。 */
    private suspend fun restoreLinkPreviewEntities(entities: List<LinkPreviewData>): RestoreCounter {
        if (entities.isEmpty()) return RestoreCounter()
        var counter = RestoreCounter()
        entities.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
            val existing = appDatabase.linkPreviewDao()
                .loadByLinksForBackup(chunk.map { it.link })
                .associateBy { it.link }
            val toWrite = mutableListOf<LinkPreviewData>()
            var inserted = 0
            var updated = 0
            var skipped = 0
            chunk.forEach { entity ->
                val local = existing[entity.link]
                when {
                    local == null -> {
                        inserted++
                        toWrite += entity
                    }
                    local.sameBackupContent(entity) -> skipped++
                    else -> {
                        updated++
                        toWrite += entity
                    }
                }
            }
            if (toWrite.isNotEmpty()) appDatabase.linkPreviewDao().upsertAllForBackup(toWrite)
            counter += RestoreCounter(inserted, updated, skipped)
        }
        return counter
    }

    /** 从 JSONL 恢复搜索历史，按范围和规范化关键词生成幂等报告。 */
    private suspend fun restoreJsonlSearchHistories(ref: BackupPackageRef): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<SearchHistoryData>()
        packageReader.readJsonLines(ref, SEARCH_HISTORIES_JSONL_PATH, BackupSearchHistory.serializer()) { item ->
            chunk += item.toEntity()
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreSearchHistoryEntities(chunk.toList())
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreSearchHistoryEntities(chunk.toList())
        return counter
    }

    /** 恢复搜索历史，业务主键是搜索范围和规范化关键词。 */
    private suspend fun restoreSearchHistoryEntities(entities: List<SearchHistoryData>): RestoreCounter {
        if (entities.isEmpty()) return RestoreCounter()
        var counter = RestoreCounter()
        entities.groupBy { it.isFolded }.forEach { (isFolded, scopedEntities) ->
            scopedEntities.chunked(WRITE_CHUNK_SIZE).forEach { chunk ->
                val existing = appDatabase.searchHistoryDao()
                    .loadByScopeAndQueriesForBackup(isFolded, chunk.map { it.normalizedQuery })
                    .associateBy { it.normalizedQuery }
                val toWrite = mutableListOf<SearchHistoryData>()
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
                if (toWrite.isNotEmpty()) appDatabase.searchHistoryDao().upsertAllForBackup(toWrite)
                counter += RestoreCounter(inserted, updated, skipped)
            }
        }
        return counter
    }

    /** 从 JSONL 恢复视频下载记录，按 chunk 查询已有记录。 */
    private suspend fun restoreJsonlVideoDownloads(ref: BackupPackageRef, manifest: BackupManifest): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<BackupVideoDownload>()
        packageReader.readJsonLines(ref, VIDEO_DOWNLOADS_JSONL_PATH, BackupVideoDownload.serializer()) { item ->
            chunk += item
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreVideoDownloadChunk(chunk, manifest)
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreVideoDownloadChunk(chunk, manifest)
        return counter
    }

    /** 恢复视频下载 chunk；旧备份中的运行中任务在 mapper 中降级为失败。 */
    private suspend fun restoreVideoDownloadChunk(backups: List<BackupVideoDownload>, manifest: BackupManifest): RestoreCounter {
        val existing = appDatabase.downloadDao().loadTasksByIdsForBackup(backups.map { it.id }).associateBy { it.id }
        val toWrite = mutableListOf<DownloadTaskData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        backups.forEach { backup ->
            val entity = backup.toEntity()
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }
                local.updateTime > manifest.createdAt || local.updateTime >= entity.updateTime -> skipped++
                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        if (toWrite.isNotEmpty()) appDatabase.downloadDao().upsertTasksForBackup(toWrite)
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 从 JSONL 恢复图片批次，按 chunk 查询已有记录。 */
    private suspend fun restoreJsonlImageBatches(ref: BackupPackageRef, manifest: BackupManifest): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<BackupImageBatch>()
        packageReader.readJsonLines(ref, IMAGE_BATCHES_JSONL_PATH, BackupImageBatch.serializer()) { item ->
            chunk += item
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreImageBatchChunk(chunk, manifest)
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreImageBatchChunk(chunk, manifest)
        return counter
    }

    /** 恢复图片批次 chunk；本地较新的批次状态会保留。 */
    private suspend fun restoreImageBatchChunk(backups: List<BackupImageBatch>, manifest: BackupManifest): RestoreCounter {
        val existing = appDatabase.imageExtractDao().loadBatchesByIdsForBackup(backups.map { it.id }).associateBy { it.id }
        val toWrite = mutableListOf<ImageExtractBatchData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        backups.forEach { backup ->
            val entity = backup.toEntity()
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }
                local.updateTime > manifest.createdAt || local.updateTime >= entity.updateTime -> skipped++
                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        if (toWrite.isNotEmpty()) appDatabase.imageExtractDao().upsertBatchesForBackup(toWrite)
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 从 JSONL 恢复图片项，按 chunk 查询已有 id。 */
    private suspend fun restoreJsonlImageItems(ref: BackupPackageRef): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<BackupImageItem>()
        packageReader.readJsonLines(ref, IMAGE_ITEMS_JSONL_PATH, BackupImageItem.serializer()) { item ->
            chunk += item
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreImageItemChunk(chunk)
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreImageItemChunk(chunk)
        return counter
    }

    /** 恢复图片项 chunk；只有元数据确实变化时才写入，重复恢复同一备份会计入跳过。 */
    private suspend fun restoreImageItemChunk(backups: List<BackupImageItem>): RestoreCounter {
        val entities = backups.map { it.toEntity() }
        val existing = appDatabase.imageExtractDao().loadItemsByIdsForBackup(entities.map { it.id }).associateBy { it.id }
        val toWrite = mutableListOf<ImageExtractItemData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        entities.forEach { entity ->
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }
                local.sameBackupContent(entity) -> skipped++
                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        if (toWrite.isNotEmpty()) appDatabase.imageExtractDao().upsertItemsForBackup(toWrite)
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 恢复剪贴记录，并保护本地较新的状态不被旧备份覆盖。 */
    private suspend fun restoreClips(
        snapshot: BackupSnapshot,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): RestoreCounter {
        val backupClips = snapshot.data.clips
        if (backupClips.isEmpty()) return RestoreCounter()
        val existing = appDatabase.clipDao()
            .loadClipsByIdsForBackup(backupClips.map { it.id })
            .associateBy { it.id }
        val toWrite = mutableListOf<ClipData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        val sourceApps = snapshot.data.sourceApps.associateBy { it.packageName }
        val linkPreviews = snapshot.data.linkPreviews.associateBy { it.link }
        backupClips.forEach { backup ->
            val entity = backup.toEntity(
                sourceApp = backup.sourceAppPackage?.let { sourceApps[it] },
                linkPreview = backup.link?.let { linkPreviews[it] },
                timeNormalizer = timeNormalizer
            )
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }

                local.lastUserStateTime() > timeNormalizer.normalizedManifestCreatedAt -> {
                    skipped++
                }

                local.lastUserStateTime() >= entity.lastUserStateTime() -> {
                    skipped++
                }

                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        toWrite.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.clipDao().upsertClipsForBackup(it) }
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 恢复视频下载记录；旧备份中的运行中任务会降级为失败状态。 */
    private suspend fun restoreVideoDownloads(snapshot: BackupSnapshot): RestoreCounter {
        val backups = snapshot.data.videoDownloads
        if (backups.isEmpty()) return RestoreCounter()
        val existing = appDatabase.downloadDao()
            .loadTasksByIdsForBackup(backups.map { it.id })
            .associateBy { it.id }
        val toWrite = mutableListOf<DownloadTaskData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        backups.forEach { backup ->
            val entity = backup.toEntity()
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }

                local.updateTime > snapshot.createdAt || local.updateTime >= entity.updateTime -> {
                    skipped++
                }

                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        toWrite.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.downloadDao().upsertTasksForBackup(it) }
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 恢复图片批次；本地较新的批次状态会保留。 */
    private suspend fun restoreImageBatches(snapshot: BackupSnapshot): RestoreCounter {
        val backups = snapshot.data.imageBatches
        if (backups.isEmpty()) return RestoreCounter()
        val existing = appDatabase.imageExtractDao()
            .loadBatchesByIdsForBackup(backups.map { it.id })
            .associateBy { it.id }
        val toWrite = mutableListOf<ImageExtractBatchData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        backups.forEach { backup ->
            val entity = backup.toEntity()
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }

                local.updateTime > snapshot.createdAt || local.updateTime >= entity.updateTime -> {
                    skipped++
                }

                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        toWrite.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.imageExtractDao().upsertBatchesForBackup(it) }
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 恢复图片项；只有元数据确实变化时才写入，重复恢复同一备份会计入跳过。 */
    private suspend fun restoreImageItems(snapshot: BackupSnapshot): RestoreCounter {
        val entities = snapshot.data.imageItems.map { it.toEntity() }
        if (entities.isEmpty()) return RestoreCounter()
        val existing = appDatabase.imageExtractDao()
            .loadItemsByIdsForBackup(entities.map { it.id })
            .associateBy { it.id }
        val toWrite = mutableListOf<ImageExtractItemData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        entities.forEach { entity ->
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }
                local.sameBackupContent(entity) -> skipped++
                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        toWrite.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.imageExtractDao().upsertItemsForBackup(it) }
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 恢复跨安装有意义的设置白名单。 */
    private fun restoreSettings(settings: BackupSettings) {
        settings.clipItemQuickAction?.let { value ->
            AppSetting.clipItemQuickAction = ClipItemQuickAction.fromStorageValue(value)
        }
        settings.recycleBinRetentionDays?.let { days ->
            AppSetting.recycleBinRetentionDays = days
        }
    }

}
