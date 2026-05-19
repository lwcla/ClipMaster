package com.cla.clip.base.general.backup

import androidx.room.withTransaction
import com.cla.clip.base.general.BuildConfig
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
import com.cla.clip.base.general.utils.logE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份恢复仓库。
 *
 * 该类集中负责从 Room/MMKV 导出统一快照、校验备份、生成预检摘要和执行幂等恢复；页面和 WebDAV 客户端只处理文件
 * 读写，不直接理解数据库表结构，避免后续新增表时多处散落备份逻辑。
 */
@Singleton
class BackupRepository @Inject constructor(
    /** 应用 Room 数据库，用于事务性导出/恢复所有可备份表。 */
    private val appDatabase: AppDatabase,
) {
    companion object {
        /** 日志标签，只记录脱敏状态，不输出剪贴内容或账号密码。 */
        private const val TAG = "BackupRepository"

        /** 分块写入大小，避免一次性向 Room 绑定过多实体造成内存和 SQL 参数压力。 */
        private const val WRITE_CHUNK_SIZE = 300
    }

    /**
     * 全局备份恢复互斥锁。
     *
     * 手动本地导出、WebDAV 上传、恢复写库和后续自动任务共用该锁，保证不会同时生成两个不同快照或并发恢复。
     */
    private val backupMutex = Mutex()

    /**
     * 生成完整备份快照和 manifest。
     *
     * 读取发生在 Room 事务中，确保批次与图片项、剪贴记录与关联缓存不会出现半状态；设置项读取属于 MMKV 轻量读取，
     * 与数据库事务没有强一致性要求，但只导出跨安装有意义的白名单字段。
     */
    suspend fun createSnapshot(
        source: BackupSource,
        now: Long = System.currentTimeMillis(),
    ): BackupExportResult = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            val data = appDatabase.withTransaction {
                BackupData(
                    clips = appDatabase.clipDao().loadAllClipsForBackup().map { it.toBackupClip() },
                    sourceApps = appDatabase.sourceAppDao().loadAllForBackup().map { it.toBackupSourceApp() },
                    linkPreviews = appDatabase.linkPreviewDao().loadAllForBackup().map { it.toBackupLinkPreview() },
                    searchHistories = appDatabase.searchHistoryDao().loadAllForBackup().map { it.toBackupSearchHistory() },
                    settings = AppSetting.toBackupSettings(),
                    videoDownloads = appDatabase.downloadDao().loadAllTasksForBackup().map { it.toBackupVideoDownload() },
                    imageBatches = appDatabase.imageExtractDao().loadAllBatchesForBackup().map { it.toBackupImageBatch() },
                    imageItems = appDatabase.imageExtractDao().loadAllItemsForBackup().map { it.toBackupImageItem() }
                )
            }
            val checksum = data.calculateChecksum()
            val summary = data.toSummary()
            val snapshot = BackupSnapshot(
                applicationId = BuildConfig.APPLICATION_ID,
                createdAt = now,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                deviceLabel = buildBackupDeviceLabel(AppSetting.pid),
                source = source,
                checksum = checksum,
                summary = summary,
                data = data
            )
            val packageBytes = snapshot.encodeToPackageBytes()
            val fileName = buildBackupFileName(snapshot.deviceLabel, snapshot.createdAt)
            val manifest = snapshot.toManifest(fileName, packageBytes.size.toLong())
            logD(TAG) { "createSnapshot: source=$source clips=${summary.clipCount} videos=${summary.videoDownloadCount} images=${summary.imageBatchCount}" }
            BackupExportResult(
                snapshot = snapshot,
                packageBytes = packageBytes,
                manifest = manifest,
                manifestJson = BackupJson.encodeManifest(manifest),
                fileName = fileName,
                manifestFileName = buildManifestFileName(fileName)
            )
        }
    }

    /**
     * 解析完整备份并生成预检摘要。
     *
     * 该方法只读不写，适合“只预览不恢复”；任何格式、身份或 checksum 问题都会在这里提前暴露。
     */
    suspend fun previewSnapshot(packageBytes: ByteArray): BackupPreview = withContext(Dispatchers.Default) {
        val snapshot = decodeAndValidateSnapshot(packageBytes)
        BackupPreview(
            createdAt = snapshot.createdAt,
            appVersionName = snapshot.appVersionName,
            schemaVersion = snapshot.schemaVersion,
            deviceLabel = snapshot.deviceLabel,
            checksumValid = true,
            summary = snapshot.summary
        )
    }

    /**
     * 恢复完整备份。
     *
     * 写库阶段在 Room transaction 内执行；同一备份重复恢复通过主键/upsert 和“本地较新则跳过”的规则保持幂等。
     */
    suspend fun restoreSnapshot(packageBytes: ByteArray): BackupRestoreReport = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            val snapshot = decodeAndValidateSnapshot(packageBytes)
            appDatabase.withTransaction {
                restoreSnapshotInTransaction(snapshot)
            }
        }
    }

    /** 解码并校验快照，统一处理 kotlinx.serialization 抛出的解析异常。 */
    private fun decodeAndValidateSnapshot(packageBytes: ByteArray): BackupSnapshot {
        val snapshot = runCatching { packageBytes.decodeBackupPackage() }
            .getOrElse { throwable ->
                logE(TAG, throwable) { "decodeAndValidateSnapshot: package parse failed" }
                if (throwable is BackupFailure) throw throwable else throw BackupFailure.ParseFailed(throwable)
            }
        snapshot.validateForRestore(BuildConfig.APPLICATION_ID)
        return snapshot
    }

    /** 在事务内执行实际恢复，调用方必须已经持有 Room transaction。 */
    private suspend fun restoreSnapshotInTransaction(snapshot: BackupSnapshot): BackupRestoreReport {
        var inserted = 0
        var updated = 0
        var skipped = 0

        val sourceApps = snapshot.data.sourceApps.map { it.toEntity() }
        sourceApps.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.sourceAppDao().upsertAllForBackup(it) }
        inserted += sourceApps.size

        val linkPreviews = snapshot.data.linkPreviews.map { it.toEntity() }
        linkPreviews.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.linkPreviewDao().upsertAllForBackup(it) }
        inserted += linkPreviews.size

        val clipRestore = restoreClips(snapshot)
        inserted += clipRestore.inserted
        updated += clipRestore.updated
        skipped += clipRestore.skipped

        val histories = snapshot.data.searchHistories.map { it.toEntity() }
        histories.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.searchHistoryDao().upsertAllForBackup(it) }
        inserted += histories.size

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

        logD(TAG) { "restoreSnapshot: inserted=$inserted updated=$updated skipped=$skipped" }
        return BackupRestoreReport(
            insertedCount = inserted,
            updatedCount = updated,
            skippedCount = skipped
        )
    }

    /** 恢复剪贴记录，并保护本地较新的状态不被旧备份覆盖。 */
    private suspend fun restoreClips(snapshot: BackupSnapshot): RestoreCounter {
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
                linkPreview = backup.link?.let { linkPreviews[it] }
            )
            val local = existing[entity.id]
            when {
                local == null -> {
                    inserted++
                    toWrite += entity
                }

                local.lastUserStateTime() > snapshot.createdAt -> {
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

    /** 恢复图片项；图片项没有更新时间，重复恢复时以 id 幂等覆盖同一行。 */
    private suspend fun restoreImageItems(snapshot: BackupSnapshot): RestoreCounter {
        val entities = snapshot.data.imageItems.map { it.toEntity() }
        if (entities.isEmpty()) return RestoreCounter()
        val existingIds = appDatabase.imageExtractDao()
            .loadItemsByIdsForBackup(entities.map { it.id })
            .map { it.id }
            .toSet()
        entities.chunked(WRITE_CHUNK_SIZE).forEach { appDatabase.imageExtractDao().upsertItemsForBackup(it) }
        return RestoreCounter(
            inserted = entities.count { it.id !in existingIds },
            updated = entities.count { it.id in existingIds },
            skipped = 0
        )
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

    /** 内部恢复计数器，用于组合各表恢复结果。 */
    private data class RestoreCounter(
        val inserted: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
    )
}

/** 一次导出的完整结果，调用方负责写入本地文件或上传 WebDAV。 */
data class BackupExportResult(
    /** 完整快照对象。 */
    val snapshot: BackupSnapshot,
    /** 完整 zip 备份包字节。 */
    val packageBytes: ByteArray,
    /** 列表 sidecar manifest。 */
    val manifest: BackupManifest,
    /** manifest JSON。 */
    val manifestJson: String,
    /** 快照文件名。 */
    val fileName: String,
    /** manifest 文件名。 */
    val manifestFileName: String,
)

/** 根据数据区生成数量摘要。 */
private fun BackupData.toSummary(): BackupSummary {
    return BackupSummary(
        clipCount = clips.size,
        sourceAppCount = sourceApps.size,
        linkPreviewCount = linkPreviews.size,
        searchHistoryCount = searchHistories.size,
        videoDownloadCount = videoDownloads.size,
        imageBatchCount = imageBatches.size,
        imageItemCount = imageItems.size
    )
}

/** 导出剪贴记录时只保留用户可见状态和恢复所需字段。 */
private fun ClipData.toBackupClip(): BackupClip {
    return BackupClip(
        id = id,
        content = content,
        timestamp = timestamp,
        pinnedTime = pinnedTime,
        isFolded = isFolded,
        foldedAt = foldedAt,
        deletedAt = deletedAt,
        link = link,
        sourceAppPackage = sourceAppPackage
    )
}

/**
 * 恢复剪贴记录时重新计算 searchText，不依赖旧备份中的派生字段。
 *
 * 搜索页依赖内容、来源 App 名称/包名和链接预览标题等组合字段；恢复时从同一备份包里的来源与预览缓存重建，
 * 避免旧备份缺少派生字段或派生字段过期导致恢复后搜索命中能力下降。
 */
private fun BackupClip.toEntity(
    sourceApp: BackupSourceApp?,
    linkPreview: BackupLinkPreview?,
): ClipData {
    val rebuiltSearchText = buildString {
        append(content)
        append(link.orEmpty())
        append(sourceApp?.appName.orEmpty())
        append(sourceAppPackage.orEmpty())
        append(linkPreview?.title.orEmpty())
        append(linkPreview?.description.orEmpty())
        append(linkPreview?.siteName.orEmpty())
    }
    return ClipData(
        id = id,
        content = content,
        timestamp = timestamp,
        pinnedTime = pinnedTime,
        isFolded = isFolded,
        foldedAt = foldedAt,
        deletedAt = deletedAt,
        link = link,
        sourceAppPackage = sourceAppPackage,
        searchText = rebuiltSearchText
    )
}

/** 估算剪贴记录最后用户状态时间，用于旧备份不覆盖本地新状态。 */
private fun ClipData.lastUserStateTime(): Long {
    return maxOf(timestamp, pinnedTime, foldedAt, deletedAt)
}

/** 来源 App 实体转备份字段。 */
private fun SourceAppData.toBackupSourceApp(): BackupSourceApp {
    return BackupSourceApp(
        packageName = packageName,
        appName = appName,
        iconPath = iconPath,
        primaryColor = primaryColor,
        iconHash = iconHash
    )
}

/** 来源 App 备份字段转实体。 */
private fun BackupSourceApp.toEntity(): SourceAppData {
    return SourceAppData(
        packageName = packageName,
        appName = appName,
        iconPath = iconPath,
        primaryColor = primaryColor,
        iconHash = iconHash
    )
}

/** 链接预览实体转备份字段。 */
private fun LinkPreviewData.toBackupLinkPreview(): BackupLinkPreview {
    return BackupLinkPreview(
        link = link,
        title = title,
        description = description,
        imageUrl = imageUrl,
        siteName = siteName
    )
}

/** 链接预览备份字段转实体。 */
private fun BackupLinkPreview.toEntity(): LinkPreviewData {
    return LinkPreviewData(
        link = link,
        title = title,
        description = description,
        imageUrl = imageUrl,
        siteName = siteName
    )
}

/** 搜索历史实体转备份字段。 */
private fun SearchHistoryData.toBackupSearchHistory(): BackupSearchHistory {
    return BackupSearchHistory(
        id = id,
        query = query,
        normalizedQuery = normalizedQuery,
        isFolded = isFolded,
        updatedAt = updatedAt
    )
}

/** 搜索历史备份字段转实体。 */
private fun BackupSearchHistory.toEntity(): SearchHistoryData {
    return SearchHistoryData(
        id = id,
        query = query,
        normalizedQuery = normalizedQuery,
        isFolded = isFolded,
        updatedAt = updatedAt
    )
}

/** AppSetting 转备份设置白名单。 */
private fun AppSetting.toBackupSettings(): BackupSettings {
    return BackupSettings(
        clipItemQuickAction = clipItemQuickAction.storageValue,
        recycleBinRetentionDays = recycleBinRetentionDays
    )
}

/** 视频任务实体转备份字段，过滤 Cookie、Referer、UA 和 pending 输出。 */
private fun DownloadTaskData.toBackupVideoDownload(): BackupVideoDownload {
    return BackupVideoDownload(
        id = id,
        videoUrl = videoUrl,
        progress = progress,
        status = status,
        errorMsg = errorMsg,
        savePathHint = savePath,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        createTime = createTime,
        updateTime = updateTime,
        fileName = fileName
    )
}

/** 视频任务备份字段转实体，运行中/合并中任务恢复为失败，避免重装后恢复后台任务。 */
private fun BackupVideoDownload.toEntity(): DownloadTaskData {
    val restoredStatus = when (status) {
        DownloadTaskData.STATUS_DOWNLOADING,
        DownloadTaskData.STATUS_MERGING -> DownloadTaskData.STATUS_FAILED
        else -> status
    }
    val restoredError = if (restoredStatus == DownloadTaskData.STATUS_FAILED && errorMsg.isNullOrBlank()) {
        "restore_interrupted"
    } else {
        errorMsg
    }
    return DownloadTaskData(
        id = id,
        videoUrl = videoUrl,
        referer = null,
        userAgent = null,
        cookie = null,
        progress = progress.coerceIn(0, 100),
        status = restoredStatus,
        errorMsg = restoredError,
        savePath = savePathHint,
        pendingOutputUri = null,
        totalSize = totalSize,
        downloadedSize = downloadedSize,
        createTime = createTime,
        updateTime = updateTime,
        fileName = fileName
    )
}

/** 图片批次实体转备份字段。 */
private fun ImageExtractBatchData.toBackupImageBatch(): BackupImageBatch {
    return BackupImageBatch(
        id = id,
        pageUrl = pageUrl,
        pageName = pageName,
        status = status,
        totalCount = totalCount,
        successCount = successCount,
        failedCount = failedCount,
        filteredCount = filteredCount,
        outputDirHint = outputDir,
        errorMsg = errorMsg,
        createTime = createTime,
        updateTime = updateTime
    )
}

/** 图片批次备份字段转实体，下载中批次恢复为失败状态。 */
private fun BackupImageBatch.toEntity(): ImageExtractBatchData {
    val restoredStatus = if (status == ImageExtractBatchData.STATUS_DOWNLOADING) {
        ImageExtractBatchData.STATUS_FAILED
    } else {
        status
    }
    return ImageExtractBatchData(
        id = id,
        pageUrl = pageUrl,
        pageName = pageName,
        status = restoredStatus,
        totalCount = totalCount,
        successCount = successCount,
        failedCount = failedCount,
        filteredCount = filteredCount,
        outputDir = outputDirHint,
        errorMsg = errorMsg ?: if (restoredStatus == ImageExtractBatchData.STATUS_FAILED) "restore_interrupted" else null,
        createTime = createTime,
        updateTime = updateTime
    )
}

/** 图片项实体转备份字段，过滤 Cookie 和临时路径。 */
private fun ImageExtractItemData.toBackupImageItem(): BackupImageItem {
    return BackupImageItem(
        id = id,
        batchId = batchId,
        url = url,
        referer = referer,
        userAgent = userAgent,
        displayOrder = displayOrder,
        width = width,
        height = height,
        status = status,
        outputUriHint = outputUri,
        finalName = finalName,
        errorMsg = errorMsg
    )
}

/** 图片项备份字段转实体，临时路径和 Cookie 不恢复。 */
private fun BackupImageItem.toEntity(): ImageExtractItemData {
    return ImageExtractItemData(
        id = id,
        batchId = batchId,
        url = url,
        referer = referer,
        userAgent = userAgent,
        cookie = null,
        displayOrder = displayOrder,
        width = width,
        height = height,
        status = status,
        tempPath = null,
        outputUri = outputUriHint,
        finalName = finalName,
        errorMsg = errorMsg
    )
}
