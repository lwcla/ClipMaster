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
import com.cla.clip.base.general.utils.logW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
    /** 文件型备份包写入器，负责 JSONL、zip、manifest 和 checksum。 */
    private val packageWriter: BackupPackageWriter,
    /** 文件型备份包读取器，负责 manifest、checksum 和 JSONL/v1 兼容读取。 */
    private val packageReader: BackupPackageReader,
    /** 备份临时文件目录管理器。 */
    private val tempFileStore: BackupTempFileStore,
) {
    companion object {
        /** 日志标签，只记录脱敏状态，不输出剪贴内容或账号密码。 */
        private const val TAG = "BackupRepository"

        /** 分块写入大小，避免一次性向 Room 绑定过多实体造成内存和 SQL 参数压力。 */
        private const val WRITE_CHUNK_SIZE = 300

        /** 分页导出大小，在 I/O 次数和单页内存之间取一个保守平衡。 */
        private const val EXPORT_PAGE_SIZE = 500
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
        backupKind: BackupKind = source.defaultBackupKind(),
        now: Long = System.currentTimeMillis(),
        taskId: String = newBackupTaskId("export"),
    ): BackupExportResult = withContext(Dispatchers.IO) {
        backupMutex.withLock {
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
            runCatching {
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
    }

    /** 使用 v1 内存模型生成测试或旧兼容快照；主流程不再调用该方法。 */
    private suspend fun createLegacySnapshot(
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

    /**
     * 解析完整备份并生成预检摘要。
     *
     * 该方法只读不写，适合“只预览不恢复”；任何格式、身份或 checksum 问题都会在这里提前暴露。
     */
    suspend fun previewSnapshot(ref: BackupPackageRef): BackupPreview = withContext(Dispatchers.Default) {
        val manifest = packageReader.preview(ref)
        BackupPreview(
            createdAt = manifest.createdAt,
            appVersionName = manifest.appVersionName,
            schemaVersion = manifest.schemaVersion,
            deviceLabel = manifest.deviceLabel,
            backupKind = manifest.backupKind,
            checksumValid = true,
            summary = manifest.summary
        )
    }

    /** 旧字节数组预览兼容入口；仅用于测试或短期过渡，主流程应使用文件引用。 */
    suspend fun previewSnapshot(packageBytes: ByteArray): BackupPreview = withContext(Dispatchers.Default) {
        val snapshot = decodeAndValidateSnapshot(packageBytes)
        BackupPreview(
            createdAt = snapshot.createdAt,
            appVersionName = snapshot.appVersionName,
            schemaVersion = snapshot.schemaVersion,
            deviceLabel = snapshot.deviceLabel,
            backupKind = snapshot.backupKind,
            checksumValid = true,
            summary = snapshot.summary
        )
    }

    /**
     * 恢复完整备份。
     *
     * 写库阶段在 Room transaction 内执行；同一备份重复恢复通过主键/upsert 和“本地较新则跳过”的规则保持幂等。
     */
    suspend fun restoreSnapshot(ref: BackupPackageRef): BackupRestoreReport = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            val manifest = packageReader.preview(ref)
            if (manifest.schemaVersion <= 1 || manifest.dataFormat == BACKUP_DATA_FORMAT_JSON_ARRAY) {
                val snapshot = ref.requireReadable().readBytes().decodeBackupPackage()
                snapshot.validateForRestore(BuildConfig.APPLICATION_ID)
                return@withContext appDatabase.withTransaction {
                    restoreSnapshotInTransaction(snapshot)
                }
            }
            appDatabase.withTransaction {
                restorePackageInTransaction(ref, manifest)
            }
        }
    }

    /** 旧字节数组恢复兼容入口；仅用于测试或短期过渡，主流程应使用文件引用。 */
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
                logE(TAG, throwable) {
                    "备份包解析失败 reasonCode=${throwable.backupReasonCode()} type=${throwable::class.simpleName}"
                }
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

        logD(TAG) { "备份恢复写库完成 inserted=$inserted updated=$updated skipped=$skipped" }
        return BackupRestoreReport(
            insertedCount = inserted,
            updatedCount = updated,
            skippedCount = skipped,
            categoryReports = listOf(
                BackupRestoreCategoryReport(BackupProgressCategory.SourceApps, sourceApps.size, 0, 0),
                BackupRestoreCategoryReport(BackupProgressCategory.LinkPreviews, linkPreviews.size, 0, 0),
                BackupRestoreCategoryReport(BackupProgressCategory.Clips, clipRestore.inserted, clipRestore.updated, clipRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.SearchHistories, histories.size, 0, 0),
                BackupRestoreCategoryReport(BackupProgressCategory.VideoDownloads, videoRestore.inserted, videoRestore.updated, videoRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.ImageBatches, imageBatchRestore.inserted, imageBatchRestore.updated, imageBatchRestore.skipped),
                BackupRestoreCategoryReport(BackupProgressCategory.ImageItems, imageItemRestore.inserted, imageItemRestore.updated, imageItemRestore.skipped)
            )
        )
    }

    /** 在事务内执行 v2 文件型备份恢复，调用方必须已经完成 manifest/checksum 校验。 */
    private suspend fun restorePackageInTransaction(ref: BackupPackageRef, manifest: BackupManifest): BackupRestoreReport {
        val sourceApps = mutableListOf<BackupSourceApp>()
        packageReader.readJsonLines(ref, SOURCE_APPS_JSONL_PATH, BackupSourceApp.serializer()) { sourceApps += it }
        sourceApps.map { it.toEntity() }
            .chunked(WRITE_CHUNK_SIZE)
            .forEach { appDatabase.sourceAppDao().upsertAllForBackup(it) }

        val linkPreviews = mutableListOf<BackupLinkPreview>()
        packageReader.readJsonLines(ref, LINK_PREVIEWS_JSONL_PATH, BackupLinkPreview.serializer()) { linkPreviews += it }
        linkPreviews.map { it.toEntity() }
            .chunked(WRITE_CHUNK_SIZE)
            .forEach { appDatabase.linkPreviewDao().upsertAllForBackup(it) }

        val sourceAppsByPackage = sourceApps.associateBy { it.packageName }
        val linkPreviewsByLink = linkPreviews.associateBy { it.link }
        val clipRestore = restoreJsonlClips(ref, manifest, sourceAppsByPackage, linkPreviewsByLink)
        val historyRestore = restoreJsonlSearchHistories(ref)
        restoreSettings(BackupJson.decodeSettings(packageReader.readText(ref, SETTINGS_PATH)))
        val videoRestore = restoreJsonlVideoDownloads(ref, manifest)
        val imageBatchRestore = restoreJsonlImageBatches(ref, manifest)
        val imageItemRestore = restoreJsonlImageItems(ref)
        val reports = listOf(
            BackupRestoreCategoryReport(BackupProgressCategory.SourceApps, sourceApps.size, 0, 0),
            BackupRestoreCategoryReport(BackupProgressCategory.LinkPreviews, linkPreviews.size, 0, 0),
            BackupRestoreCategoryReport(BackupProgressCategory.Clips, clipRestore.inserted, clipRestore.updated, clipRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.SearchHistories, historyRestore.inserted, historyRestore.updated, historyRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.VideoDownloads, videoRestore.inserted, videoRestore.updated, videoRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.ImageBatches, imageBatchRestore.inserted, imageBatchRestore.updated, imageBatchRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.ImageItems, imageItemRestore.inserted, imageItemRestore.updated, imageItemRestore.skipped)
        )
        val inserted = reports.sumOf { it.insertedCount }
        val updated = reports.sumOf { it.updatedCount }
        val skipped = reports.sumOf { it.skippedCount }
        logD(TAG) { "备份恢复写库完成 inserted=$inserted updated=$updated skipped=$skipped schema=${manifest.schemaVersion}" }
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
    ): RestoreCounter {
        var counter = RestoreCounter()
        val chunk = mutableListOf<BackupClip>()
        packageReader.readJsonLines(ref, CLIPS_JSONL_PATH, BackupClip.serializer()) { item ->
            chunk += item
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                counter += restoreClipChunk(chunk, manifest, sourceApps, linkPreviews)
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) counter += restoreClipChunk(chunk, manifest, sourceApps, linkPreviews)
        return counter
    }

    /** 恢复剪贴 chunk，并保护本地较新的用户状态。 */
    private suspend fun restoreClipChunk(
        backups: List<BackupClip>,
        manifest: BackupManifest,
        sourceApps: Map<String, BackupSourceApp>,
        linkPreviews: Map<String, BackupLinkPreview>,
    ): RestoreCounter {
        val existing = appDatabase.clipDao().loadClipsByIdsForBackup(backups.map { it.id }).associateBy { it.id }
        val toWrite = mutableListOf<ClipData>()
        var inserted = 0
        var updated = 0
        var skipped = 0
        backups.forEach { backup ->
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
                local.lastUserStateTime() > manifest.createdAt || local.lastUserStateTime() >= entity.lastUserStateTime() -> skipped++
                else -> {
                    updated++
                    toWrite += entity
                }
            }
        }
        if (toWrite.isNotEmpty()) appDatabase.clipDao().upsertClipsForBackup(toWrite)
        return RestoreCounter(inserted, updated, skipped)
    }

    /** 从 JSONL 恢复搜索历史，唯一索引会处理同范围同关键词去重。 */
    private suspend fun restoreJsonlSearchHistories(ref: BackupPackageRef): RestoreCounter {
        var inserted = 0
        val chunk = mutableListOf<SearchHistoryData>()
        packageReader.readJsonLines(ref, SEARCH_HISTORIES_JSONL_PATH, BackupSearchHistory.serializer()) { item ->
            chunk += item.toEntity()
            inserted += 1
            if (chunk.size >= WRITE_CHUNK_SIZE) {
                appDatabase.searchHistoryDao().upsertAllForBackup(chunk.toList())
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) appDatabase.searchHistoryDao().upsertAllForBackup(chunk)
        return RestoreCounter(inserted = inserted)
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

    /** 恢复图片项 chunk；图片项没有更新时间，重复恢复以 id 幂等覆盖。 */
    private suspend fun restoreImageItemChunk(backups: List<BackupImageItem>): RestoreCounter {
        val entities = backups.map { it.toEntity() }
        val existingIds = appDatabase.imageExtractDao().loadItemsByIdsForBackup(entities.map { it.id }).map { it.id }.toSet()
        appDatabase.imageExtractDao().upsertItemsForBackup(entities)
        return RestoreCounter(
            inserted = entities.count { it.id !in existingIds },
            updated = entities.count { it.id in existingIds },
            skipped = 0
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
    ) {
        /** 合并两个恢复计数器，便于 chunk 恢复逐段累积。 */
        operator fun plus(other: RestoreCounter): RestoreCounter {
            return RestoreCounter(
                inserted = inserted + other.inserted,
                updated = updated + other.updated,
                skipped = skipped + other.skipped
            )
        }
    }

    /** 流式导出开始时记录的 high-water mark，避免长事务锁库。 */
    private data class BackupHighWaterMarks(
        val clipMaxId: Long,
        val searchHistoryMaxId: Long,
        val videoDownloadMaxId: Long,
        val imageBatchMaxId: Long,
        val imageItemMaxId: Long,
    )

    /** 导出过程中累积的数量摘要，最终会写入 manifest。 */
    private data class BackupSummaryBuilder(
        var clipCount: Int = 0,
        var sourceAppCount: Int = 0,
        var linkPreviewCount: Int = 0,
        var searchHistoryCount: Int = 0,
        var videoDownloadCount: Int = 0,
        var imageBatchCount: Int = 0,
        var imageItemCount: Int = 0,
    ) {
        /** 转为稳定外部协议中的摘要模型。 */
        fun toSummary(): BackupSummary {
            return BackupSummary(
                clipCount = clipCount,
                sourceAppCount = sourceAppCount,
                linkPreviewCount = linkPreviewCount,
                searchHistoryCount = searchHistoryCount,
                videoDownloadCount = videoDownloadCount,
                imageBatchCount = imageBatchCount,
                imageItemCount = imageItemCount
            )
        }
    }
}

/** 一次导出的完整结果，调用方负责写入本地文件或上传 WebDAV。 */
data class BackupExportResult(
    /** 完整 zip 备份包临时文件。 */
    val packageFile: File,
    /** 列表 sidecar manifest。 */
    val manifest: BackupManifest,
    /** manifest JSON。 */
    val manifestJson: String,
    /** 快照文件名。 */
    val fileName: String,
    /** manifest 文件名。 */
    val manifestFileName: String,
    /** 本次导出的临时目录；调用方发布完成后可以清理。 */
    val taskDir: File,
) {
    /** 备份包大小，单位字节。 */
    val fileSize: Long
        get() = packageFile.length()
}

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
