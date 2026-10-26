package com.cla.clip.base.general.backup

import androidx.room.withTransaction
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Room 已恢复但 DataStore 设置提交失败；报告保留数据库阶段的真实结果。 */
class BackupSettingsRestoreFailure(
    /** 已完成的 Room 分类报告，调用方可据此显示部分成功。 */
    val roomReport: BackupRestoreReport,
    cause: Throwable,
) : Exception("backup_settings_restore_failed", cause)

/** 宿主 Room 已提交而可选功能库恢复失败；保留已完成阶段报告供页面重试。 */
class BackupFeatureRestoreFailure(
    /** 已提交到宿主 Room 的分类报告。 */
    val roomReport: BackupRestoreReport,
    cause: Throwable,
) : Exception("backup_feature_restore_failed", cause)

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
    /** 备份设置白名单在 Room 事务完成后单次提交到 DataStore。 */
    private val clipSettingsRepository: ClipSettingsRepository,
    /** 可选功能恢复参与者；未编译对应模块时集合为空。 */
    private val featureContributors: Set<@JvmSuppressWildcards BackupFeatureContributor>,
) {
    companion object {
        /** 日志标签，只记录恢复数量和时间归一化摘要，不输出备份内容。 */
        private const val TAG = "BackupSnapshotRestorer"

        /** 分块写入大小，避免一次性向 Room 绑定过多实体造成内存和 SQL 参数压力。 */
        private const val WRITE_CHUNK_SIZE = 300
    }

    /** 读取设置白名单，再依次提交 Room 与 DataStore；两种存储不宣称原子恢复。 */
    internal suspend fun restorePackage(
        ref: BackupPackageRef,
        manifest: BackupManifest,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): BackupRestoreReport {
        /** 已校验备份包中的设置白名单；先解析，避免 Room 完成后才发现格式损坏。 */
        val settings = BackupJson.decodeSettings(packageReader.readText(ref, SETTINGS_PATH))
        /** 仅包含宿主 Room 表的事务恢复结果。 */
        val roomReport = appDatabase.withTransaction {
            restorePackageInTransaction(ref, manifest, timeNormalizer)
        }
        /** 功能模块可能使用独立数据库，不能嵌在宿主 Room 事务中制造虚假原子性。 */
        val featureReports = try {
            featureContributors.sortedBy { it.contributorId }.flatMap { contributor -> contributor.restoreJsonl(ref, manifest) }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            logW(TAG) { "备份可选功能阶段失败 reasonCode=feature_restore_failed type=${failure::class.simpleName} roomInserted=${roomReport.insertedCount}" }
            throw BackupFeatureRestoreFailure(roomReport, failure)
        }
        /** 合并已完成的宿主和功能分类计数，再进入设置提交阶段。 */
        val report = roomReport.withFeatureReports(featureReports)
        try {
            restoreSettings(settings)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            logW(TAG) { "备份设置阶段失败 reasonCode=settings_restore_failed type=${failure::class.simpleName} completedInserted=${report.insertedCount}" }
            throw BackupSettingsRestoreFailure(report, failure)
        }
        logI(TAG) { "备份分阶段恢复完成 inserted=${report.insertedCount} updated=${report.updatedCount} skipped=${report.skippedCount}" }
        return report
    }

    /** 在事务内恢复格式 5 的宿主业务数据，完全不读写下载运行态；，调用方必须已经完成 manifest/checksum 校验。 */
    private suspend fun restorePackageInTransaction(
        ref: BackupPackageRef,
        manifest: BackupManifest,
        timeNormalizer: BackupRestoreTimeNormalizer,
    ): BackupRestoreReport {
        // 同包来源缓存，同时用于重建剪贴搜索文本。
        val sourceApps = mutableListOf<BackupSourceApp>()
        packageReader.readJsonLines(ref, SOURCE_APPS_JSONL_PATH, BackupSourceApp.serializer()) { sourceApps += it }
        // 来源表本次实际新增、更新和跳过统计。
        val sourceAppRestore = restoreSourceAppEntities(sourceApps.map { it.toEntity() })

        // 同包链接缓存，用于恢复记录及搜索派生字段。

        val linkPreviews = mutableListOf<BackupLinkPreview>()
        packageReader.readJsonLines(ref, LINK_PREVIEWS_JSONL_PATH, BackupLinkPreview.serializer()) { linkPreviews += it }
        // 链接预览表的实际恢复统计。
        val linkPreviewRestore = restoreLinkPreviewEntities(linkPreviews.map { it.toEntity() })

        // 以包名关联同包来源信息。

        val sourceAppsByPackage = sourceApps.associateBy { it.packageName }
        // 以原链接关联同包预览信息。
        val linkPreviewsByLink = linkPreviews.associateBy { it.link }
        // 保留时间归一化及较新本地状态保护。
        val clipRestore = restoreJsonlClips(ref, manifest, sourceAppsByPackage, linkPreviewsByLink, timeNormalizer)
        // 按既有唯一键恢复搜索历史。
        val historyRestore = restoreJsonlSearchHistories(ref)
        // 仅包含宿主可备份业务类别，不读写下载任务表。
        val reports = listOf(
            BackupRestoreCategoryReport(BackupProgressCategory.SourceApps, sourceAppRestore.inserted, sourceAppRestore.updated, sourceAppRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.LinkPreviews, linkPreviewRestore.inserted, linkPreviewRestore.updated, linkPreviewRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.Clips, clipRestore.inserted, clipRestore.updated, clipRestore.skipped),
            BackupRestoreCategoryReport(BackupProgressCategory.SearchHistories, historyRestore.inserted, historyRestore.updated, historyRestore.skipped),
        )
        // 本事务已新增的真实总数。
        val inserted = reports.sumOf { it.insertedCount }
        // 本事务已更新的真实总数。
        val updated = reports.sumOf { it.updatedCount }
        // 本事务重复或较新本地数据的跳过总数。
        val skipped = reports.sumOf { it.skippedCount }
        logD(TAG) {
            "备份宿主 Room 阶段完成 inserted=$inserted updated=$updated skipped=$skipped schema=${manifest.schemaVersion} " +
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

    /** 恢复跨安装设置白名单，并保留本机新增的来源过滤规则。 */
    private suspend fun restoreSettings(settings: BackupSettings) {
        /** 备份中的规范化来源名单；无效项由共享规则过滤。 */
        val backupBlockedPackages = ClipSourceBlockRules.normalizePackageSet(settings.blockedClipSourcePackages)
        /** 一次事务提交全部可备份设置，并在实际变化时原子递增版本。 */
        clipSettingsRepository.restoreFromBackup(
            quickAction = settings.clipItemQuickAction?.let(ClipItemQuickAction::fromStorageValue),
            recycleBinRetentionDays = settings.recycleBinRetentionDays,
            blockedPackages = backupBlockedPackages.toList(),
        )
    }

    /** 合并分阶段恢复计数，不改变各数据库已经提交的真实边界。 */
    private fun BackupRestoreReport.withFeatureReports(featureReports: List<BackupRestoreCategoryReport>): BackupRestoreReport = copy(
        insertedCount = insertedCount + featureReports.sumOf { it.insertedCount },
        updatedCount = updatedCount + featureReports.sumOf { it.updatedCount },
        skippedCount = skippedCount + featureReports.sumOf { it.skippedCount },
        categoryReports = categoryReports + featureReports,
    )

}
