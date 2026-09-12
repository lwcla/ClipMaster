package com.cla.clip.feature.torrent.search.backup

import androidx.room.withTransaction
import com.cla.clip.base.general.backup.BackupFeatureContributor
import com.cla.clip.base.general.backup.BackupJson
import com.cla.clip.base.general.backup.BackupManifest
import com.cla.clip.base.general.backup.BackupPackageBuildSession
import com.cla.clip.base.general.backup.BackupPackageFile
import com.cla.clip.base.general.backup.BackupPackageReader
import com.cla.clip.base.general.backup.BackupPackageRef
import com.cla.clip.base.general.backup.BackupRestoreCategoryReport
import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentDao
import com.cla.clip.feature.torrent.search.data.local.BookmarkedTorrentEntity
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchDatabase
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryDao
import com.cla.clip.feature.torrent.search.data.local.TorrentSearchHistoryEntity
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentDao
import com.cla.clip.feature.torrent.search.data.local.ViewedTorrentEntity
import com.cla.clip.feature.torrent.search.data.model.BookmarkedTorrent
import com.cla.clip.feature.torrent.search.data.model.BookmarkContractValidator
import com.cla.clip.feature.torrent.search.input.TorrentSearchInputNormalizer
import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.SortCriteria
import com.cla.clip.feature.torrent.search.model.contract.SortOrder
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.settings.TorrentSearchSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** schema v4 的 TorrentSearch 功能贡献器，负责白名单分页导出与稳定键幂等恢复。 */
@Singleton
internal class TorrentSearchBackupContributor @Inject constructor(
    /** feature-local Room 数据库，用于每个恢复分页的原子冲突判断与写入。 */
    private val database: TorrentSearchDatabase,
    /** 书签分页与恢复 DAO。 */
    private val bookmarkDao: BookmarkedTorrentDao,
    /** 搜索历史分页与恢复 DAO。 */
    private val historyDao: TorrentSearchHistoryDao,
    /** 已查看分页与恢复 DAO。 */
    private val viewedDao: ViewedTorrentDao,
    /** 只读取和恢复默认排序的设置仓库。 */
    private val settingsRepository: TorrentSearchSettingsRepository,
    /** 复用 base 的校验后 ZIP entry 读取器。 */
    private val packageReader: BackupPackageReader,
    /** 恢复产生真实数据变化后写入统一 dirty 标记。 */
    private val backupDirtyMarker: TorrentSearchBackupDirtyMarker,
) : BackupFeatureContributor {
    /** 稳定参与者 ID，只用于排序和低敏诊断。 */
    override val contributorId: String = CONTRIBUTOR_ID

    /** 在导出开始读取三张表的时间上界，后续写入留给下一次 dirty 备份。 */
    override suspend fun readHighWaterMarks(): Map<String, Long> {
        return linkedMapOf(
            BOOKMARK_HIGH_WATER_MARK to bookmarkDao.readBackupHighWaterMark(),
            HISTORY_HIGH_WATER_MARK to historyDao.readBackupHighWaterMark(),
            VIEWED_HIGH_WATER_MARK to viewedDao.readBackupHighWaterMark(),
        )
    }

    /** 分页写出三类 JSONL 和单个设置 JSON，不构造全量内存快照。 */
    override suspend fun exportJsonl(
        /** base 创建的当前备份包构建会话。 */
        session: BackupPackageBuildSession,
        /** 导出开始时读取的三张表固定时间上界。 */
        highWaterMarks: Map<String, Long>,
        /** 写回 manifest 的脱敏功能分类计数。 */
        featureCounts: MutableMap<String, Int>,
    ): List<BackupPackageFile> {
        /** files 收集本参与者实际写入的四个白名单 entry。 */
        val files = mutableListOf<BackupPackageFile>()
        /** bookmarkCount 是写入 manifest 的脱敏书签数量。 */
        var bookmarkCount = 0
        /** sink 是 base 提供的逐行书签写出端。 */
        files += session.writeJsonLines(BOOKMARK_PATH, TorrentSearchBackupBookmarkV1.serializer()) { sink ->
            /** record 是当前分页映射后的单条书签协议。 */
            bookmarkCount = exportBookmarks(highWaterMarks[BOOKMARK_HIGH_WATER_MARK] ?: -1L) { record -> sink.write(record) }
        }
        /** historyCount 是写入 manifest 的脱敏历史数量。 */
        var historyCount = 0
        /** sink 是 base 提供的逐行历史写出端。 */
        files += session.writeJsonLines(HISTORY_PATH, TorrentSearchBackupHistoryV1.serializer()) { sink ->
            /** record 是当前分页映射后的单条历史协议。 */
            historyCount = exportHistory(highWaterMarks[HISTORY_HIGH_WATER_MARK] ?: -1L) { record -> sink.write(record) }
        }
        /** viewedCount 是写入 manifest 的脱敏已查看数量。 */
        var viewedCount = 0
        /** sink 是 base 提供的逐行已查看写出端。 */
        files += session.writeJsonLines(VIEWED_PATH, TorrentSearchBackupViewedV1.serializer()) { sink ->
            /** record 是当前分页映射后的单条已查看协议。 */
            viewedCount = exportViewed(highWaterMarks[VIEWED_HIGH_WATER_MARK] ?: -1L) { record -> sink.write(record) }
        }
        /** settings 是当前快照中唯一允许跨安装恢复的 TorrentSearch 设置组合。 */
        val settings = settingsRepository.settings.first()
        /** backupSettings 明确只映射默认排序，不复制任何本机网络、隐私或凭据状态。 */
        val backupSettings = TorrentSearchBackupSettingsV1(
            sortCriteriaCode = settings.sortCriteria.code,
            sortOrderCode = settings.sortOrder.code,
        )
        files += session.writeJsonObject(SETTINGS_PATH, BackupJson.json.encodeToString(backupSettings))
        featureCounts[BOOKMARK_COUNT_KEY] = bookmarkCount
        featureCounts[HISTORY_COUNT_KEY] = historyCount
        featureCounts[VIEWED_COUNT_KEY] = viewedCount
        featureCounts[SETTINGS_COUNT_KEY] = 1
        return files
    }

    /** 仅恢复 manifest 中存在的本功能 entry，并逐分类返回新增、更新和跳过计数。 */
    override suspend fun restoreJsonl(ref: BackupPackageRef, manifest: BackupManifest): List<BackupRestoreCategoryReport> {
        /** availablePaths 只来自已经通过 base checksum 校验的 manifest。 */
        val availablePaths = manifest.files.mapTo(hashSetOf()) { packageFile -> packageFile.path }
        /** reports 按固定书签、历史、已查看、设置顺序返回。 */
        val reports = mutableListOf<BackupRestoreCategoryReport>()
        if (BOOKMARK_PATH in availablePaths) reports += restoreBookmarks(ref)
        if (HISTORY_PATH in availablePaths) reports += restoreHistory(ref)
        if (VIEWED_PATH in availablePaths) reports += restoreViewed(ref)
        if (SETTINGS_PATH in availablePaths) reports += restoreSettings(ref)
        /** legacyCount 只形成“不兼容并跳过”报告，不读取或迁移旧 entry 正文。 */
        val legacyCount = legacyTorrentSearchSkippedCount(manifest)
        if (legacyCount > 0) {
            reports += BackupRestoreCategoryReport(TorrentSearchBackupContract.LEGACY_INCOMPATIBLE_REPORT_CODE, 0, 0, legacyCount)
        }
        return reports
    }

    /** 按 `(bookmarkedAt, infoHash)` 游标导出书签并返回真实写入数量。 */
    private suspend fun exportBookmarks(
        /** 导出开始时固定的最大书签时间。 */
        highWaterMark: Long,
        /** 单条协议写出回调，不保留全量列表。 */
        write: (TorrentSearchBackupBookmarkV1) -> Unit,
    ): Int {
        /** cursorTime 是上一页最后一条的时间游标。 */
        var cursorTime = Long.MIN_VALUE
        /** cursorKey 是同时间记录的稳定主键游标。 */
        var cursorKey = ""
        /** count 是实际输出记录数。 */
        var count = 0
        while (true) {
            /** page 是当前固定上界内的一页 Room 行。 */
            val page = bookmarkDao.readBackupPage(highWaterMark, cursorTime, cursorKey, PAGE_SIZE)
            if (page.isEmpty()) break
            /** entity 是逐条映射为显式外部协议的书签行。 */
            for (entity in page) {
                write(entity.toBackupRecord())
                count += 1
            }
            /** last 是本页严格排序后的最后一条，用作下一页游标。 */
            val last = page.last()
            cursorTime = last.bookmarkedAtEpochMillis
            cursorKey = last.infoHash
        }
        return count
    }

    /** 按 `(updatedAt, normalizedQuery)` 游标导出历史并返回真实写入数量。 */
    private suspend fun exportHistory(
        /** 导出开始时固定的最大历史时间。 */
        highWaterMark: Long,
        /** 单条协议写出回调，不保留全量列表。 */
        write: (TorrentSearchBackupHistoryV1) -> Unit,
    ): Int {
        /** cursorTime 是上一页最后一条的时间游标。 */
        var cursorTime = Long.MIN_VALUE
        /** cursorKey 是同时间记录的稳定主键游标。 */
        var cursorKey = ""
        /** count 是实际输出记录数。 */
        var count = 0
        while (true) {
            /** page 是当前固定上界内的一页 Room 行。 */
            val page = historyDao.readBackupPage(highWaterMark, cursorTime, cursorKey, PAGE_SIZE)
            if (page.isEmpty()) break
            /** entity 是逐条映射为显式外部协议的历史行。 */
            for (entity in page) {
                write(entity.toBackupRecord())
                count += 1
            }
            /** last 是本页严格排序后的最后一条，用作下一页游标。 */
            val last = page.last()
            cursorTime = last.updatedAtEpochMillis
            cursorKey = last.normalizedQuery
        }
        return count
    }

    /** 按 `(viewedAt, infoHash)` 游标导出已查看并返回真实写入数量。 */
    private suspend fun exportViewed(
        /** 导出开始时固定的最大查看时间。 */
        highWaterMark: Long,
        /** 单条协议写出回调，不保留全量列表。 */
        write: (TorrentSearchBackupViewedV1) -> Unit,
    ): Int {
        /** cursorTime 是上一页最后一条的时间游标。 */
        var cursorTime = Long.MIN_VALUE
        /** cursorKey 是同时间记录的稳定主键游标。 */
        var cursorKey = ""
        /** count 是实际输出记录数。 */
        var count = 0
        while (true) {
            /** page 是当前固定上界内的一页 Room 行。 */
            val page = viewedDao.readBackupPage(highWaterMark, cursorTime, cursorKey, PAGE_SIZE)
            if (page.isEmpty()) break
            /** entity 是逐条映射为显式外部协议的已查看行。 */
            for (entity in page) {
                write(entity.toBackupRecord())
                count += 1
            }
            /** last 是本页严格排序后的最后一条，用作下一页游标。 */
            val last = page.last()
            cursorTime = last.viewedAtEpochMillis
            cursorKey = last.infoHash
        }
        return count
    }

    /** 分块恢复书签；非法协议行计入 skipped，不向数据库传递部分数据。 */
    private suspend fun restoreBookmarks(ref: BackupPackageRef): BackupRestoreCategoryReport {
        /** counter 汇总本分类的稳定恢复报告。 */
        val counter = RestoreCounter()
        /** buffer 限制单次冲突查询和 Room 事务规模。 */
        val buffer = ArrayList<TorrentSearchBackupBookmarkV1>(PAGE_SIZE)
        /** record 是当前从已校验 JSONL 解码的书签协议。 */
        packageReader.readJsonLines(ref, BOOKMARK_PATH, TorrentSearchBackupBookmarkV1.serializer()) { record ->
            buffer += record
            if (buffer.size >= PAGE_SIZE) flushBookmarks(buffer, counter)
        }
        flushBookmarks(buffer, counter)
        counter.markDirtyIfChanged(backupDirtyMarker)
        return counter.toReport(BOOKMARK_REPORT_CODE)
    }

    /** 校验并在单个 Room 事务内合并一页书签。 */
    private suspend fun flushBookmarks(buffer: MutableList<TorrentSearchBackupBookmarkV1>, counter: RestoreCounter) {
        if (buffer.isEmpty()) return
        /** validatedCandidates 是完整通过书签协议校验的实体，非法行先计入跳过。 */
        val validatedCandidates = buffer.mapNotNull { record ->
            /** entity 是当前书签协议的完整校验结果。 */
            record.toEntityOrNull().also { entity -> if (entity == null) counter.skippedCount += 1 }
        }
        /** deduplicated 保证同一事务内每个 infoHash 只参与一次冲突判断，并把多余行计入跳过。 */
        val deduplicated = deduplicateTorrentSearchRestoreCandidates(
            candidates = validatedCandidates,
            stableKey = BookmarkedTorrentEntity::infoHash,
            timestamp = BookmarkedTorrentEntity::bookmarkedAtEpochMillis,
        )
        counter.skippedCount += deduplicated.duplicateCount
        /** candidates 是当前事务内稳定键唯一且时间最新的合法书签。 */
        val candidates = deduplicated.candidates
        database.withTransaction {
            /** localByKey 是当前事务内用于冲突判断的本地书签索引。 */
            val localByKey = bookmarkDao.findByInfoHashes(candidates.map(BookmarkedTorrentEntity::infoHash))
                .associateBy(BookmarkedTorrentEntity::infoHash)
            /** mutations 只包含缺失或备份时间更新的合法记录。 */
            val mutations = candidates.filter { candidate ->
                /** local 是相同稳定键的本地记录。 */
                val local = localByKey[candidate.infoHash]
                when (decideTorrentSearchRestore(candidate.bookmarkedAtEpochMillis, local?.bookmarkedAtEpochMillis)) {
                    TorrentSearchRestoreDecision.INSERT -> true.also { counter.insertedCount += 1 }
                    TorrentSearchRestoreDecision.UPDATE -> true.also { counter.updatedCount += 1 }
                    TorrentSearchRestoreDecision.SKIP -> false.also { counter.skippedCount += 1 }
                }
            }
            if (mutations.isNotEmpty()) bookmarkDao.upsertAll(mutations)
        }
        buffer.clear()
    }

    /** 分块恢复搜索历史；规范化键与展示文本不一致时整条跳过。 */
    private suspend fun restoreHistory(ref: BackupPackageRef): BackupRestoreCategoryReport {
        /** counter 汇总本分类的稳定恢复报告。 */
        val counter = RestoreCounter()
        /** buffer 限制单次冲突查询和 Room 事务规模。 */
        val buffer = ArrayList<TorrentSearchBackupHistoryV1>(PAGE_SIZE)
        /** record 是当前从已校验 JSONL 解码的历史协议。 */
        packageReader.readJsonLines(ref, HISTORY_PATH, TorrentSearchBackupHistoryV1.serializer()) { record ->
            buffer += record
            if (buffer.size >= PAGE_SIZE) flushHistory(buffer, counter)
        }
        flushHistory(buffer, counter)
        counter.markDirtyIfChanged(backupDirtyMarker)
        return counter.toReport(HISTORY_REPORT_CODE)
    }

    /** 校验并在单个 Room 事务内合并一页搜索历史。 */
    private suspend fun flushHistory(buffer: MutableList<TorrentSearchBackupHistoryV1>, counter: RestoreCounter) {
        if (buffer.isEmpty()) return
        /** validatedCandidates 是展示值、规范化键和时间都合法的历史实体。 */
        val validatedCandidates = buffer.mapNotNull { record ->
            /** entity 是当前历史协议的完整校验结果。 */
            record.toEntityOrNull().also { entity -> if (entity == null) counter.skippedCount += 1 }
        }
        /** deduplicated 保证同一事务内每个规范化查询只按最新时间恢复一次。 */
        val deduplicated = deduplicateTorrentSearchRestoreCandidates(
            candidates = validatedCandidates,
            stableKey = TorrentSearchHistoryEntity::normalizedQuery,
            timestamp = TorrentSearchHistoryEntity::updatedAtEpochMillis,
        )
        counter.skippedCount += deduplicated.duplicateCount
        /** candidates 是当前事务内稳定键唯一且时间最新的合法历史。 */
        val candidates = deduplicated.candidates
        database.withTransaction {
            /** localByKey 是当前事务内用于冲突判断的本地历史索引。 */
            val localByKey = historyDao.findByNormalizedQueries(candidates.map(TorrentSearchHistoryEntity::normalizedQuery))
                .associateBy(TorrentSearchHistoryEntity::normalizedQuery)
            /** mutations 只包含缺失或备份时间更新的合法记录。 */
            val mutations = candidates.filter { candidate ->
                /** local 是相同稳定键的本地记录。 */
                val local = localByKey[candidate.normalizedQuery]
                when (decideTorrentSearchRestore(candidate.updatedAtEpochMillis, local?.updatedAtEpochMillis)) {
                    TorrentSearchRestoreDecision.INSERT -> true.also { counter.insertedCount += 1 }
                    TorrentSearchRestoreDecision.UPDATE -> true.also { counter.updatedCount += 1 }
                    TorrentSearchRestoreDecision.SKIP -> false.also { counter.skippedCount += 1 }
                }
            }
            if (mutations.isNotEmpty()) historyDao.upsertAll(mutations)
        }
        buffer.clear()
    }

    /** 分块恢复已查看记录，保持本地较新时间不被旧备份覆盖。 */
    private suspend fun restoreViewed(ref: BackupPackageRef): BackupRestoreCategoryReport {
        /** counter 汇总本分类的稳定恢复报告。 */
        val counter = RestoreCounter()
        /** buffer 限制单次冲突查询和 Room 事务规模。 */
        val buffer = ArrayList<TorrentSearchBackupViewedV1>(PAGE_SIZE)
        /** record 是当前从已校验 JSONL 解码的已查看协议。 */
        packageReader.readJsonLines(ref, VIEWED_PATH, TorrentSearchBackupViewedV1.serializer()) { record ->
            buffer += record
            if (buffer.size >= PAGE_SIZE) flushViewed(buffer, counter)
        }
        flushViewed(buffer, counter)
        counter.markDirtyIfChanged(backupDirtyMarker)
        return counter.toReport(VIEWED_REPORT_CODE)
    }

    /** 校验并在单个 Room 事务内合并一页已查看记录。 */
    private suspend fun flushViewed(buffer: MutableList<TorrentSearchBackupViewedV1>, counter: RestoreCounter) {
        if (buffer.isEmpty()) return
        /** validatedCandidates 是身份和时间都合法的已查看实体。 */
        val validatedCandidates = buffer.mapNotNull { record ->
            /** entity 是当前已查看协议的完整校验结果。 */
            record.toEntityOrNull().also { entity -> if (entity == null) counter.skippedCount += 1 }
        }
        /** deduplicated 保证同一事务内每个 infoHash 只恢复最新查看时间。 */
        val deduplicated = deduplicateTorrentSearchRestoreCandidates(
            candidates = validatedCandidates,
            stableKey = ViewedTorrentEntity::infoHash,
            timestamp = ViewedTorrentEntity::viewedAtEpochMillis,
        )
        counter.skippedCount += deduplicated.duplicateCount
        /** candidates 是当前事务内稳定键唯一且时间最新的合法已查看记录。 */
        val candidates = deduplicated.candidates
        database.withTransaction {
            /** localByKey 是当前事务内用于冲突判断的本地已查看索引。 */
            val localByKey = viewedDao.findByInfoHashes(candidates.map(ViewedTorrentEntity::infoHash))
                .associateBy(ViewedTorrentEntity::infoHash)
            /** mutations 只包含缺失或备份时间更新的合法记录。 */
            val mutations = candidates.filter { candidate ->
                /** local 是相同稳定键的本地记录。 */
                val local = localByKey[candidate.infoHash]
                when (decideTorrentSearchRestore(candidate.viewedAtEpochMillis, local?.viewedAtEpochMillis)) {
                    TorrentSearchRestoreDecision.INSERT -> true.also { counter.insertedCount += 1 }
                    TorrentSearchRestoreDecision.UPDATE -> true.also { counter.updatedCount += 1 }
                    TorrentSearchRestoreDecision.SKIP -> false.also { counter.skippedCount += 1 }
                }
            }
            if (mutations.isNotEmpty()) viewedDao.upsertAll(mutations)
        }
        buffer.clear()
    }

    /** 恢复设置白名单；版本或稳定 code 不受支持时拒绝整个设置 entry。 */
    private suspend fun restoreSettings(ref: BackupPackageRef): BackupRestoreCategoryReport {
        /** record 是从已校验 ZIP entry 解码的设置子协议。 */
        val record = BackupJson.json.decodeFromString<TorrentSearchBackupSettingsV1>(packageReader.readText(ref, SETTINGS_PATH))
        if (record.version != TORRENT_SEARCH_SETTINGS_VERSION) throw SerializationException("Unsupported TorrentSearch settings version")
        /** criteria 只接受当前明确声明的稳定 code，不使用未知值回退。 */
        val criteria = SortCriteria.entries.firstOrNull { candidate -> candidate.code == record.sortCriteriaCode }
            ?: throw SerializationException("Unsupported TorrentSearch sort criteria")
        /** order 只接受当前明确声明的稳定 code，不使用未知值回退。 */
        val order = SortOrder.entries.firstOrNull { candidate -> candidate.code == record.sortOrderCode }
            ?: throw SerializationException("Unsupported TorrentSearch sort order")
        /** local 是恢复前的排序快照，用于生成准确 updated/skipped 报告。 */
        val local = settingsRepository.settings.first()
        if (local.sortCriteria == criteria && local.sortOrder == order) {
            return BackupRestoreCategoryReport(SETTINGS_REPORT_CODE, 0, 0, 1)
        }
        settingsRepository.setSort(criteria, order)
        return BackupRestoreCategoryReport(SETTINGS_REPORT_CODE, 0, 1, 0)
    }

    /** 本功能备份 entry、计数和分页上限的稳定常量。 */
    companion object {
        /** 参与者排序与诊断稳定 ID。 */
        private const val CONTRIBUTOR_ID = "torrent_search"
        /** 书签 JSONL entry。 */
        internal const val BOOKMARK_PATH = TorrentSearchBackupContract.BOOKMARK_PATH
        /** 搜索历史 JSONL entry。 */
        internal const val HISTORY_PATH = TorrentSearchBackupContract.HISTORY_PATH
        /** 已查看 JSONL entry。 */
        internal const val VIEWED_PATH = TorrentSearchBackupContract.VIEWED_PATH
        /** 非敏感设置 JSON entry。 */
        internal const val SETTINGS_PATH = TorrentSearchBackupContract.SETTINGS_PATH
        /** 书签 manifest/report 稳定 code。 */
        internal const val BOOKMARK_REPORT_CODE = TorrentSearchBackupContract.BOOKMARK_REPORT_CODE
        /** 搜索历史 manifest/report 稳定 code。 */
        internal const val HISTORY_REPORT_CODE = TorrentSearchBackupContract.HISTORY_REPORT_CODE
        /** 已查看 manifest/report 稳定 code。 */
        internal const val VIEWED_REPORT_CODE = TorrentSearchBackupContract.VIEWED_REPORT_CODE
        /** 设置 manifest/report 稳定 code。 */
        internal const val SETTINGS_REPORT_CODE = TorrentSearchBackupContract.SETTINGS_REPORT_CODE
        /** 书签 manifest 数量稳定 key。 */
        internal const val BOOKMARK_COUNT_KEY = TorrentSearchBackupContract.BOOKMARK_COUNT_KEY
        /** 搜索历史 manifest 数量稳定 key。 */
        internal const val HISTORY_COUNT_KEY = TorrentSearchBackupContract.HISTORY_COUNT_KEY
        /** 已查看 manifest 数量稳定 key。 */
        internal const val VIEWED_COUNT_KEY = TorrentSearchBackupContract.VIEWED_COUNT_KEY
        /** 设置 manifest 数量稳定 key。 */
        internal const val SETTINGS_COUNT_KEY = TorrentSearchBackupContract.SETTINGS_COUNT_KEY
        /** 书签导出上界 key。 */
        private const val BOOKMARK_HIGH_WATER_MARK = "bookmarks_max_time"
        /** 历史导出上界 key。 */
        private const val HISTORY_HIGH_WATER_MARK = "histories_max_time"
        /** 已查看导出上界 key。 */
        private const val VIEWED_HIGH_WATER_MARK = "viewed_max_time"
        /** 单页与单次恢复事务记录上限。 */
        private const val PAGE_SIZE = 300
    }
}

/** 根据旧 entry 与脱敏 manifest 计数生成不兼容跳过数，不读取旧用户内容。 */
internal fun legacyTorrentSearchSkippedCount(manifest: BackupManifest): Int {
    /** hasLegacyEntry 必须由旧磁力固定路径精确命中，未知文件不算旧数据。 */
    val hasLegacyEntry = manifest.files.any { packageFile -> packageFile.path in TorrentSearchBackupContract.legacyPaths }
    if (!hasLegacyEntry) return 0
    /** declaredCount 优先使用旧 contributor 已写入的两类脱敏数量。 */
    val declaredCount = manifest.summary.featureCounts.getOrDefault("magnet_search_history_count", 0) +
        manifest.summary.featureCounts.getOrDefault("magnet_download_record_count", 0)
    return declaredCount.coerceAtLeast(1)
}

/** 时间戳冲突判断结果；稳定键缺失新增，备份较新更新，其余跳过。 */
internal enum class TorrentSearchRestoreDecision {
    /** 本地没有相同稳定键。 */
    INSERT,
    /** 备份记录严格新于本地。 */
    UPDATE,
    /** 本地相同或更新，重复恢复保持不变。 */
    SKIP,
}

/** 使用同一纯规则决定三张表的幂等恢复行为。 */
internal fun decideTorrentSearchRestore(backupTimestamp: Long, localTimestamp: Long?): TorrentSearchRestoreDecision {
    return when {
        localTimestamp == null -> TorrentSearchRestoreDecision.INSERT
        backupTimestamp > localTimestamp -> TorrentSearchRestoreDecision.UPDATE
        else -> TorrentSearchRestoreDecision.SKIP
    }
}

/** 单个恢复分页去重结果；多余稳定键行独立计入跳过，确保报告总数准确。 */
internal data class TorrentSearchRestoreDeduplication<T>(
    /** 保留原首次出现顺序、但同键内容已替换为最新时间的候选。 */
    val candidates: List<T>,
    /** 被相同稳定键折叠的行数。 */
    val duplicateCount: Int,
)

/** 在进入 Room 事务前按稳定键折叠重复候选，并保留时间戳最新的一条。 */
internal fun <T> deduplicateTorrentSearchRestoreCandidates(
    /** 已通过协议校验的当前分页候选。 */
    candidates: List<T>,
    /** 提取数据库稳定键的纯函数。 */
    stableKey: (T) -> String,
    /** 提取冲突时间戳的纯函数。 */
    timestamp: (T) -> Long,
): TorrentSearchRestoreDeduplication<T> {
    /** newestByKey 用插入顺序保证恢复与报告稳定，同时允许较新重复记录替换值。 */
    val newestByKey = linkedMapOf<String, T>()
    /** candidate 是当前待折叠的合法恢复候选。 */
    candidates.forEach { candidate ->
        /** key 是当前候选的数据库稳定身份。 */
        val key = stableKey(candidate)
        /** previous 是本分页已见到的同键候选。 */
        val previous = newestByKey[key]
        if (previous == null || timestamp(candidate) > timestamp(previous)) {
            newestByKey[key] = candidate
        }
    }
    return TorrentSearchRestoreDeduplication(
        candidates = newestByKey.values.toList(),
        duplicateCount = candidates.size - newestByKey.size,
    )
}

/** 恢复分类计数器，只保留脱敏数量。 */
private class RestoreCounter(
    /** 新增记录数。 */
    var insertedCount: Int = 0,
    /** 更新记录数。 */
    var updatedCount: Int = 0,
    /** 本地较新、重复或非法记录数。 */
    var skippedCount: Int = 0,
) {
    /** 仅在真实写入后标记 dirty。 */
    fun markDirtyIfChanged(marker: TorrentSearchBackupDirtyMarker) {
        if (insertedCount + updatedCount > 0) marker.markDirty()
    }

    /** 生成 base 可聚合的稳定恢复报告。 */
    fun toReport(category: String): BackupRestoreCategoryReport {
        return BackupRestoreCategoryReport(category, insertedCount, updatedCount, skippedCount)
    }
}

/** 将 Room 书签行映射为 v1 外部协议。 */
private fun BookmarkedTorrentEntity.toBackupRecord(): TorrentSearchBackupBookmarkV1 {
    return TorrentSearchBackupBookmarkV1(
        infoHash = infoHash,
        name = name,
        providerId = providerId,
        providerName = providerName,
        sizeText = sizeText,
        seeders = seeders,
        peers = peers,
        uploadEpochMillis = uploadEpochMillis,
        categoryCode = categoryCode,
        descriptionPageUrl = descriptionPageUrl,
        magnetUri = magnetUri,
        fileDownloadLink = fileDownloadLink,
        bookmarkedAtEpochMillis = bookmarkedAtEpochMillis,
    )
}

/** 将已解码 v1 书签完整校验后映射为 Room 行；失败返回 null。 */
private fun TorrentSearchBackupBookmarkV1.toEntityOrNull(): BookmarkedTorrentEntity? {
    /** providerId 必须继续满足稳定 Provider 身份契约。 */
    val providerId = runCatching { ProviderId(providerId) }.getOrNull() ?: return null
    /** seedersValue 只允许 UInt 能表达的非负范围。 */
    val seedersValue = seeders?.takeIf { value -> value in 0L..UInt.MAX_VALUE.toLong() }?.toUInt()
        ?: if (seeders != null) return null else null
    /** peersValue 只允许 UInt 能表达的非负范围。 */
    val peersValue = peers?.takeIf { value -> value in 0L..UInt.MAX_VALUE.toLong() }?.toUInt()
        ?: if (peers != null) return null else null
    /** category 只接受当前稳定 code；空值继续表示未知分类。 */
    val category = categoryCode?.let { code -> Category.entries.firstOrNull { candidate -> candidate.code == code } ?: return null }
    /** canonical 复用导入、普通保存和导出的完整单项校验器。 */
    val canonical = BookmarkContractValidator.normalize(
        BookmarkedTorrent(
            infoHash = infoHash,
            name = name,
            providerId = providerId,
            providerName = providerName,
            size = sizeText,
            seeders = seedersValue,
            peers = peersValue,
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            descriptionPageUrl = descriptionPageUrl,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
            bookmarkedAtEpochMillis = bookmarkedAtEpochMillis,
        )
    ) ?: return null
    return BookmarkedTorrentEntity(
        infoHash = canonical.infoHash,
        name = canonical.name,
        providerId = canonical.providerId.value,
        providerName = canonical.providerName,
        sizeText = canonical.size,
        seeders = canonical.seeders?.toLong(),
        peers = canonical.peers?.toLong(),
        uploadEpochMillis = canonical.uploadEpochMillis,
        categoryCode = canonical.category?.code,
        descriptionPageUrl = canonical.descriptionPageUrl,
        magnetUri = canonical.magnetUri,
        fileDownloadLink = canonical.fileDownloadLink,
        bookmarkedAtEpochMillis = canonical.bookmarkedAtEpochMillis,
    )
}

/** 将 Room 搜索历史行映射为 v1 外部协议。 */
private fun TorrentSearchHistoryEntity.toBackupRecord(): TorrentSearchBackupHistoryV1 {
    return TorrentSearchBackupHistoryV1(normalizedQuery, query, updatedAtEpochMillis)
}

/** 校验展示查询与稳定键的一致性后映射为 Room 行；失败返回 null。 */
private fun TorrentSearchBackupHistoryV1.toEntityOrNull(): TorrentSearchHistoryEntity? {
    if (updatedAtEpochMillis < 0L) return null
    /** normalizedInput 复用搜索入口对控制字符、空白和长度的统一规则。 */
    val normalizedInput = TorrentSearchInputNormalizer.normalize(query)
    if (!normalizedInput.canAutoSearch) return null
    /** expectedKey 使用 Locale.ROOT 固定跨设备大小写规则。 */
    val expectedKey = normalizedInput.query.lowercase(Locale.ROOT)
    if (expectedKey != normalizedQuery) return null
    return TorrentSearchHistoryEntity(expectedKey, normalizedInput.query, updatedAtEpochMillis)
}

/** 将 Room 已查看行映射为 v1 外部协议。 */
private fun ViewedTorrentEntity.toBackupRecord(): TorrentSearchBackupViewedV1 {
    return TorrentSearchBackupViewedV1(infoHash, viewedAtEpochMillis)
}

/** 校验稳定 infoHash 与时间后映射为 Room 行；失败返回 null。 */
private fun TorrentSearchBackupViewedV1.toEntityOrNull(): ViewedTorrentEntity? {
    if (viewedAtEpochMillis < 0L) return null
    /** normalizedHash 只接受 SHA-1 的 40 位 ASCII 十六进制表示。 */
    val normalizedHash = infoHash.trim().lowercase(Locale.ROOT)
    /** character 是待检查的单个稳定身份字符。 */
    if (normalizedHash.length != 40 || normalizedHash.any { character -> character !in '0'..'9' && character !in 'a'..'f' }) return null
    return ViewedTorrentEntity(normalizedHash, viewedAtEpochMillis)
}
