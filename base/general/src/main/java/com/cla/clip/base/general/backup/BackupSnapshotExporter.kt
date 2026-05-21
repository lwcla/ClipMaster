package com.cla.clip.base.general.backup

import androidx.room.withTransaction
import com.cla.clip.base.general.BuildConfig
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份快照导出器。
 *
 * Exporter 只负责从 Room/MMKV 读取可备份白名单字段、分页写入 JSONL、生成 manifest 和临时 zip；不处理恢复合并、
 * 本地 SAF 发布或 WebDAV 上传，避免 `BackupRepository` 同时承担导出、恢复和外部 IO 细节。
 */
@Singleton
class BackupSnapshotExporter @Inject constructor(
    /** 应用 Room 数据库，用于读取各备份表和 high-water mark。 */
    private val appDatabase: AppDatabase,
    /** 文件型备份包写入器，负责 JSONL、zip、manifest 和 checksum。 */
    private val packageWriter: BackupPackageWriter,
    /** 备份临时文件目录管理器。 */
    private val tempFileStore: BackupTempFileStore,
) {
    companion object {
        /** 日志标签，只记录脱敏数量和状态，不输出业务内容。 */
        private const val TAG = "BackupSnapshotExporter"

        /** 分页导出大小，在 I/O 次数和单页内存之间取一个保守平衡。 */
        private const val EXPORT_PAGE_SIZE = 500
    }

    /**
     * 生成完整备份快照和 manifest。
     *
     * 导出开始时读取 high-water mark，后续分页只导出这些边界内的数据；导出期间新增变化由 dirty 机制留给下一次备份。
     */
    suspend fun export(
        source: BackupSource,
        backupKind: BackupKind,
        now: Long,
        taskId: String,
    ): BackupExportResult {
        val taskDir = tempFileStore.createExportDir(taskId)
        tempFileStore.ensureAvailableSpace(BackupPackageWriter.MAX_BACKUP_PACKAGE_BYTES)
        val deviceLabel = buildBackupDeviceLabel(AppSetting.pid)
        val fileName = buildBackupFileName(deviceLabel, now, backupKind)
        val session = packageWriter.begin(taskDir, taskId, fileName)
        val highWater = readHighWaterMarks()
        logD(TAG) {
            "备份导出读取 highWater taskId=$taskId clipMaxId=${highWater.clipMaxId} " +
                "searchHistoryMaxId=${highWater.searchHistoryMaxId} videoDownloadMaxId=${highWater.videoDownloadMaxId} " +
                "imageBatchMaxId=${highWater.imageBatchMaxId} imageItemMaxId=${highWater.imageItemMaxId}"
        }
        val entryFiles = mutableListOf<BackupPackageFile>()
        val summaryBuilder = BackupSummaryBuilder()
        return runCatching {
            entryFiles += exportClipLines(session, highWater.clipMaxId, summaryBuilder)
            entryFiles += exportSourceAppLines(session, summaryBuilder)
            entryFiles += exportLinkPreviewLines(session, summaryBuilder)
            entryFiles += exportSearchHistoryLines(session, highWater.searchHistoryMaxId, summaryBuilder)
            entryFiles += session.writeJsonObject(SETTINGS_PATH, BackupJson.encodeSettings(AppSetting.toBackupSettings()))
            entryFiles += exportVideoDownloadLines(session, highWater.videoDownloadMaxId, summaryBuilder)
            entryFiles += exportImageBatchLines(session, highWater.imageBatchMaxId, summaryBuilder)
            entryFiles += exportImageItemLines(session, highWater.imageItemMaxId, summaryBuilder)
            val summary = summaryBuilder.toSummary()
            val manifest = BackupManifest(
                applicationId = BuildConfig.APPLICATION_ID,
                schemaVersion = BACKUP_SCHEMA_VERSION,
                createdAt = now,
                appVersionCode = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                deviceLabel = deviceLabel,
                source = source,
                backupKind = backupKind,
                snapshotFileName = fileName,
                checksum = entryFiles.calculateManifestChecksum(),
                files = entryFiles,
                dataFormat = BACKUP_DATA_FORMAT_JSONL,
                summary = summary
            )
            val packageResult = packageWriter.finish(session, manifest)
            logD(TAG) {
                "备份快照已生成 source=${source.logCode()} backupKind=${backupKind.logCode()} " +
                    "fileSize=${packageResult.fileSize} ${summary.toLogFields()}"
            }
            BackupExportResult(
                packageFile = packageResult.packageFile,
                manifest = packageResult.manifest,
                manifestJson = packageResult.manifestJson,
                fileName = packageResult.fileName,
                manifestFileName = packageResult.manifestFileName,
                taskDir = packageResult.taskDir
            )
        }.getOrElse { throwable ->
            tempFileStore.cleanupTaskDir(taskDir, taskId)
            throw throwable
        }
    }

    /** 使用 v1 内存模型生成测试或旧兼容快照；主流程不再调用该方法。 */
    internal suspend fun createLegacySnapshot(
        source: BackupSource,
        backupKind: BackupKind,
        now: Long,
    ): BackupSnapshot {
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
        return BackupSnapshot(
            applicationId = BuildConfig.APPLICATION_ID,
            schemaVersion = 1,
            createdAt = now,
            appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME,
            deviceLabel = buildBackupDeviceLabel(AppSetting.pid),
            source = source,
            backupKind = backupKind,
            checksum = data.calculateChecksum(),
            summary = data.toSummary(),
            data = data
        )
    }

    /** 读取各表 high-water mark，本次导出只包含这些边界内的记录。 */
    private suspend fun readHighWaterMarks(): BackupHighWaterMarks {
        return BackupHighWaterMarks(
            clipMaxId = appDatabase.clipDao().maxClipIdForBackup(),
            searchHistoryMaxId = appDatabase.searchHistoryDao().maxIdForBackup(),
            videoDownloadMaxId = appDatabase.downloadDao().maxTaskIdForBackup(),
            imageBatchMaxId = appDatabase.imageExtractDao().maxBatchIdForBackup(),
            imageItemMaxId = appDatabase.imageExtractDao().maxItemIdForBackup()
        )
    }

    /** 分页导出剪贴记录为 JSONL。 */
    private suspend fun exportClipLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(CLIPS_JSONL_PATH, BackupClip.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = appDatabase.clipDao().loadClipsPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupClip())
                    summary.clipCount += 1
                    exportedCount += 1
                }
                lastId = page.last().id
            }
            if (exportedCount == 0) {
                // 分页导出是 v2 主路径；这里仅在分页通道异常读空但旧全量查询能读到数据时兜底，避免生成“结构正确但业务为空”的备份。
                val fallback = appDatabase.clipDao().loadAllClipsForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "剪贴分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty maxId=$maxId fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupClip())
                        summary.clipCount += 1
                    }
                }
            }
        }
    }

    /** 分页导出来源 App 缓存为 JSONL。 */
    private suspend fun exportSourceAppLines(
        session: BackupPackageBuildSession,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(SOURCE_APPS_JSONL_PATH, BackupSourceApp.serializer()) { sink ->
            var lastPackage = ""
            while (true) {
                val page = appDatabase.sourceAppDao().loadPageForBackup(lastPackage, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupSourceApp())
                    summary.sourceAppCount += 1
                    exportedCount += 1
                }
                lastPackage = page.last().packageName
            }
            if (exportedCount == 0) {
                // 主键为字符串的分页依赖字典序游标；若 Provider/排序边界异常导致读空，使用旧全量导出优先保证备份可恢复。
                val fallback = appDatabase.sourceAppDao().loadAllForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "来源 App 分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupSourceApp())
                        summary.sourceAppCount += 1
                    }
                }
            }
        }
    }

    /** 分页导出链接预览缓存为 JSONL。 */
    private suspend fun exportLinkPreviewLines(
        session: BackupPackageBuildSession,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(LINK_PREVIEWS_JSONL_PATH, BackupLinkPreview.serializer()) { sink ->
            var lastLink = ""
            while (true) {
                val page = appDatabase.linkPreviewDao().loadPageForBackup(lastLink, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupLinkPreview())
                    summary.linkPreviewCount += 1
                    exportedCount += 1
                }
                lastLink = page.last().link
            }
            if (exportedCount == 0) {
                // 链接预览是缓存型数据，分页失败时允许用旧全量查询兜底，避免恢复后剪贴链接缺少预览关联。
                val fallback = appDatabase.linkPreviewDao().loadAllForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "链接预览分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupLinkPreview())
                        summary.linkPreviewCount += 1
                    }
                }
            }
        }
    }

    /** 分页导出搜索历史为 JSONL。 */
    private suspend fun exportSearchHistoryLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(SEARCH_HISTORIES_JSONL_PATH, BackupSearchHistory.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = appDatabase.searchHistoryDao().loadPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupSearchHistory())
                    summary.searchHistoryCount += 1
                    exportedCount += 1
                }
                lastId = page.last().id
            }
            if (exportedCount == 0) {
                // 搜索历史数量通常较小；分页异常读空时用旧全量查询兜底，避免用户历史提示在恢复后丢失。
                val fallback = appDatabase.searchHistoryDao().loadAllForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "搜索历史分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty maxId=$maxId fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupSearchHistory())
                        summary.searchHistoryCount += 1
                    }
                }
            }
        }
    }

    /** 分页导出视频下载记录为 JSONL。 */
    private suspend fun exportVideoDownloadLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(VIDEO_DOWNLOADS_JSONL_PATH, BackupVideoDownload.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = appDatabase.downloadDao().loadTasksPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupVideoDownload())
                    summary.videoDownloadCount += 1
                    exportedCount += 1
                }
                lastId = page.last().id
            }
            if (exportedCount == 0) {
                // 下载记录恢复价值高；分页通道读空时兜底旧全量查询，同时 mapper 仍会过滤敏感字段。
                val fallback = appDatabase.downloadDao().loadAllTasksForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "视频下载分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty maxId=$maxId fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupVideoDownload())
                        summary.videoDownloadCount += 1
                    }
                }
            }
        }
    }

    /** 分页导出图片批次为 JSONL。 */
    private suspend fun exportImageBatchLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(IMAGE_BATCHES_JSONL_PATH, BackupImageBatch.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = appDatabase.imageExtractDao().loadBatchesPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupImageBatch())
                    summary.imageBatchCount += 1
                    exportedCount += 1
                }
                lastId = page.last().id
            }
            if (exportedCount == 0) {
                // 图片批次是媒体记录的父级数据；分页读空时兜底全量查询，保证批次和图片项恢复关系仍可重建。
                val fallback = appDatabase.imageExtractDao().loadAllBatchesForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "图片批次分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty maxId=$maxId fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupImageBatch())
                        summary.imageBatchCount += 1
                    }
                }
            }
        }
    }

    /** 分页导出图片项为 JSONL。 */
    private suspend fun exportImageItemLines(
        session: BackupPackageBuildSession,
        maxId: Long,
        summary: BackupSummaryBuilder,
    ): BackupPackageFile {
        var exportedCount = 0
        return session.writeJsonLines(IMAGE_ITEMS_JSONL_PATH, BackupImageItem.serializer()) { sink ->
            var lastId = 0L
            while (true) {
                val page = appDatabase.imageExtractDao().loadItemsPageForBackup(lastId, maxId, EXPORT_PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { data ->
                    sink.write(data.toBackupImageItem())
                    summary.imageItemCount += 1
                    exportedCount += 1
                }
                lastId = page.last().id
            }
            if (exportedCount == 0) {
                // 图片项可能很多，正常必须走分页；只有分页异常完全读空时才兜底旧全量查询，避免生成无图片记录的备份。
                val fallback = appDatabase.imageExtractDao().loadAllItemsForBackup()
                if (fallback.isNotEmpty()) {
                    logW(TAG) {
                        "图片项分页导出为空，已使用全量查询兜底 reasonCode=paged_export_empty maxId=$maxId fallbackCount=${fallback.size}"
                    }
                    fallback.forEach { data ->
                        sink.write(data.toBackupImageItem())
                        summary.imageItemCount += 1
                    }
                }
            }
        }
    }
}
