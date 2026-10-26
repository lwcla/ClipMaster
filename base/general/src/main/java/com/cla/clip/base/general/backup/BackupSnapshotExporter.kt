package com.cla.clip.base.general.backup

import com.cla.clip.base.general.BuildConfig
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.config.installation.InstallationIdentityRepository
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份快照导出器。
 *
 * Exporter 从 Room 和已提交的 DataStore 快照读取可备份白名单字段，分页写入 JSONL、生成 manifest 和临时 zip；不处理恢复合并、
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
    /** 只读取剪贴设置域的可备份白名单。 */
    private val clipSettingsRepository: ClipSettingsRepository,
    /** 本机安装身份只用于生成设备标签，不进入设置备份。 */
    private val installationIdentityRepository: InstallationIdentityRepository,
    /** 可选功能备份参与者；未编译对应模块时集合为空。 */
    private val featureContributors: Set<@JvmSuppressWildcards BackupFeatureContributor>,
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
     * 导出开始时读取 high-water mark，后续分页只导出这些边界内的数据；新增变化在下一次每日或手动备份中读取。
     */
    suspend fun export(
        source: BackupSource,
        backupKind: BackupKind,
        now: Long,
        taskId: String,
    ): BackupExportResult {
        /** 本次快照的独立临时目录；后续任何准备或导出失败都须清理。 */
        val taskDir = tempFileStore.createExportDir(taskId)
        return try {
            tempFileStore.ensureAvailableSpace(BackupPackageWriter.MAX_BACKUP_PACKAGE_BYTES)
            /** 快照名称使用本机安装 ID，不写入备份设置白名单。 */
            val deviceLabel = buildBackupDeviceLabel(installationIdentityRepository.getOrCreateInstallId())
            /** 导出开始时读取一次已提交设置，避免 ZIP 内字段跨不同写入时刻。 */
            val backupSettings = clipSettingsRepository.snapshot().toBackupSettings()
            // 文件名前缀包含任务身份；即使两轮在同一秒生成，也不会覆盖对方的正式 ZIP/manifest。
            val fileName = buildBackupFileName("${deviceLabel}_$taskId", now, backupKind)
            // 本轮独立写入会话，临时文件只在完整打包后发布。
            val session = packageWriter.begin(taskDir, taskId, fileName)
            // 当前可备份表的读取上界，不访问三张媒体任务表。
            val highWater = readHighWaterMarks()
            logD(TAG) {
                "备份导出读取 highWater taskId=$taskId clipMaxId=${highWater.clipMaxId} " +
                    "searchHistoryMaxId=${highWater.searchHistoryMaxId} " +
                    "featureContributors=${highWater.featureMarks.keys.sorted()}"
            }
            // 宿主和可选模块已写完的文件清单。
            val entryFiles = mutableListOf<BackupPackageFile>()
            // 只统计本轮实际写出的数据。
            val summaryBuilder = BackupSummaryBuilder()
            run {
                entryFiles += exportClipLines(session, highWater.clipMaxId, summaryBuilder)
                entryFiles += exportSourceAppLines(session, summaryBuilder)
                entryFiles += exportLinkPreviewLines(session, summaryBuilder)
                entryFiles += exportSearchHistoryLines(session, highWater.searchHistoryMaxId, summaryBuilder)
                entryFiles += session.writeJsonObject(SETTINGS_PATH, BackupJson.encodeSettings(backupSettings))
                featureContributors.sortedBy { it.contributorId }.forEach { contributor ->
                    entryFiles += contributor.exportJsonl(
                        session = session,
                        highWaterMarks = highWater.featureMarks[contributor.contributorId].orEmpty(),
                        featureCounts = summaryBuilder.featureCounts
                    )
                }
                // 写入 manifest 的稳定摘要。
                val summary = summaryBuilder.toSummary()
                // 当前版本的外部文件协议，清单和 checksum 在发布前确定。
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
                // 完成后的 ZIP 与 sidecar 摘要，由调用方继续发布。
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
            }
        } catch (failure: Throwable) {
            runCatching { tempFileStore.cleanupTaskDir(taskDir, taskId) }
                .onFailure { cleanupFailure ->
                    logW(TAG) { "备份导出失败后的临时目录清理未完成 taskId=$taskId reasonCode=temp_cleanup_failed type=${cleanupFailure::class.simpleName}" }
                }
            throw failure
        }
    }

    /** 读取各表 high-water mark，本次导出只包含这些边界内的记录。 */
    private suspend fun readHighWaterMarks(): BackupHighWaterMarks {
        return BackupHighWaterMarks(
            clipMaxId = appDatabase.clipDao().maxClipIdForBackup(),
            searchHistoryMaxId = appDatabase.searchHistoryDao().maxIdForBackup(),
            featureMarks = featureContributors
                .sortedBy { it.contributorId }
                .associate { contributor -> contributor.contributorId to contributor.readHighWaterMarks() }
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

}
