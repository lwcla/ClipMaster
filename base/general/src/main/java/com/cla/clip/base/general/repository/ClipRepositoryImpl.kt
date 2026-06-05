package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.dao.AppDatabase
import com.cla.clip.base.general.dao.ClipDao
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.LinkPreviewDao
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SourceAppDao
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.entity.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ClipRepository的默认实现。
 * 通过构造函数注入ClipDao，并委托所有数据操作给它。
 * 使用 @Inject constructor() 使Hilt能够创建这个类的实例。
 */
class ClipRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val clipDao: ClipDao,
    private val sourceAppDao: SourceAppDao,
    private val linkPreviewDao: LinkPreviewDao,
) : ClipRepository {

    companion object {
        /** SQLite 单条语句可绑定参数数量有限，批量 id 操作按 500 分块，给 Room 生成 SQL 留出余量。 */
        private const val ID_BATCH_SIZE = 500

        /** 回收站保留天数的最小值，避免 0 或负数导致保存设置后立即清空全部数据。 */
        private const val MIN_RETENTION_DAYS = 1

        /** 回收站保留天数的最大值，防止异常输入换算毫秒时溢出或形成过大的业务承诺。 */
        private const val MAX_RETENTION_DAYS = 3650

        /**
         * FTS 查询只保留字母和数字参与 MATCH。
         *
         * 这样做是为了过滤双引号、冒号、星号等会改变 FTS 语法的字符；代价是纯符号搜索会退回 LIKE 查询。
         */
        private val ftsSearchableCharRegex = Regex("[\\p{L}\\p{N}]")
    }

    // 使用 withContext(Dispatchers.IO) 确保所有数据库写操作都在IO线程上执行。
    // Flow 本身是异步的，Room会自动处理其线程，所以读操作不需要显式切换。
    override fun searchAllClips(userInput: String): Flow<List<ClipShowEntity>> {
        val trimmed = userInput.trim()

        // FTS 查询：逐字分词，用于模糊匹配
        val ftsQuery = trimmed
            .toCharArray()
            .joinToString("* ") + "*"  // "微* 信*"

        // 精确匹配查询
        val exactQuery = trimmed

        // 包含某词查询（这里假设以空格分割为多个词）
        val queryWord = trimmed
            .split(" ")
            .filter { it.isNotBlank() }
            .firstOrNull()  // 取第一个词
            ?: trimmed

        return clipDao.searchAllClips(
            query = ftsQuery,
            exactQuery = exactQuery,
            queryWord = queryWord
        ).map { it.toUi() }
    }

    override fun searchClips(
        userInput: String,
        startTime: Long?,
        endTime: Long?,
        sourceAppPackages: Set<String>,
        visibilityScope: ClipVisibilityScope,
    ): PagingSource<Int, ClipDetail> {
        val trimmed = userInput.trim()
        val sourcePackagesForQuery = buildSourcePackagesForQuery(sourceAppPackages)
        // 空字符串包名本身代表“未知来源”筛选项，不能按空白过滤；只有数量为 0 才表示跳过来源过滤。
        val sourceAppPackageCount = sourceAppPackages
            .map { it.trim() }
            .distinct()
            .size
        val isFolded = visibilityScope.toFoldState()
        // 折叠搜索的“今天/近 7 天”等时间筛选按折叠动作发生时间过滤，和折叠列表排序、卡片时间展示保持一致。
        val timeFilterUsesFoldedAt = visibilityScope == ClipVisibilityScope.FoldedOnly
        if (trimmed.isBlank()) {
            return clipDao.searchClipsByFilters(
                startTime = startTime,
                endTime = endTime,
                sourceAppPackageCount = sourceAppPackageCount,
                sourceAppPackages = sourcePackagesForQuery,
                isFolded = isFolded,
                timeFilterUsesFoldedAt = timeFilterUsesFoldedAt
            )
        }

        val ftsQuery = buildFtsQuery(trimmed)
        if (ftsQuery.isBlank()) {
            return clipDao.searchClipsByLike(
                keyword = trimmed,
                startTime = startTime,
                endTime = endTime,
                sourceAppPackageCount = sourceAppPackageCount,
                sourceAppPackages = sourcePackagesForQuery,
                isFolded = isFolded,
                timeFilterUsesFoldedAt = timeFilterUsesFoldedAt
            )
        }

        return clipDao.searchClipsByKeyword(
            query = ftsQuery,
            exactQuery = trimmed,
            queryWord = buildQueryWord(trimmed),
            // 中文连续词依赖 LIKE 子串兜底，必须保留完整输入，避免“来源”无法命中“来源应用的包名”。
            likeKeyword = trimmed,
            startTime = startTime,
            endTime = endTime,
            sourceAppPackageCount = sourceAppPackageCount,
            sourceAppPackages = sourcePackagesForQuery,
            isFolded = isFolded,
            timeFilterUsesFoldedAt = timeFilterUsesFoldedAt
        )
    }

    /**
     * 生成 Room `IN (:sourceAppPackages)` 可安全绑定的包名列表。
     *
     * 搜索页用空集合表达“全部来源”，但 Room 的集合参数最好始终提供至少一个占位值；
     * 因此空集合会转换成一个不会生效的哨兵值，并由 `sourceAppPackageCount = 0` 让 SQL 跳过来源过滤。
     * 空字符串包名是“未知来源”的真实查询键，必须保留到 `IN` 参数中，避免选择“未知”后被误判为全部来源。
     */
    private fun buildSourcePackagesForQuery(sourceAppPackages: Set<String>): List<String> {
        val packages = sourceAppPackages
            .map { it.trim() }
            .distinct()
            .sorted()
        return packages.ifEmpty { listOf("__all_source_apps__") }
    }

    /**
     * 把用户输入转换成 FTS 可执行的前缀查询。
     *
     * 这里延续旧搜索“逐字符前缀匹配”的策略，方便中文内容在没有明确空格分词时仍能被查到；
     * 同时过滤 FTS 特殊字符，避免用户输入 URL 或标点时触发 MATCH 语法错误。
     */
    private fun buildFtsQuery(trimmedInput: String): String {
        return trimmedInput
            .mapNotNull { char ->
                char.takeIf { ftsSearchableCharRegex.matches(it.toString()) }?.let { "$it*" }
            }
            .joinToString(separator = " ")
    }

    /**
     * 提取用于排序打分的核心查询词。
     *
     * 多词搜索时取第一个非空词，保持与旧搜索排序逻辑一致；如果分词结果为空，则回退到完整输入。
     */
    private fun buildQueryWord(trimmedInput: String): String {
        return trimmedInput
            .split(Regex("\\s+"))
            .firstOrNull { it.isNotBlank() }
            ?: trimmedInput
    }

    /**
     * 将业务层可见范围转换为数据库布尔字段。
     *
     * 这个转换集中放在 Repository，调用方只表达“普通数据”或“折叠数据”，不需要知道 Room 表字段如何编码。
     */
    private fun ClipVisibilityScope.toFoldState(): Boolean {
        return when (this) {
            ClipVisibilityScope.VisibleOnly -> false
            ClipVisibilityScope.FoldedOnly -> true
        }
    }

    override suspend fun addNewClip(captureEntity: ClipCaptureEntity): ClipSaveResult = withContext(Dispatchers.IO) {
        appDatabase.withTransaction {
            /** 本次入库的来源包名；空字符串代表未知来源筛选键，继续沿用历史存储语义。 */
            val sourcePackage = captureEntity.sourcePackage
            /** 本次入库的来源应用名；空字符串或 Unknown 会被规则视为不可信来源。 */
            val sourceAppName = captureEntity.sourceAppName
            /** 与本次内容完全相同且未进入回收站的候选记录。 */
            val candidates = clipDao.loadClipDetailsByContent(captureEntity.content)
            /** 本次保存动作决策；只负责选目标，不执行任何副作用。 */
            val saveAction = decideDuplicateClipSaveAction(
                candidates = candidates,
                incomingSourcePackage = sourcePackage,
                incomingSourceAppName = sourceAppName
            )

            if (saveAction is DuplicateClipSaveAction.SkipDuplicate) {
                return@withTransaction ClipSaveResult.SkippedDuplicate(saveAction.existingClip.clip.id)
            }

            /** 已存在的来源 App 缓存；本次没有新图标时需要保留旧图标，避免异步补图结果被空值覆盖。 */
            val existingSourceApp = sourceAppDao.loadByPackageName(sourcePackage)
            /** 本次写入数据库的来源 App；图标字段会合并旧缓存，避免异步补图被空值覆盖。 */
            val sourceApp = buildSourceAppForClip(captureEntity, existingSourceApp)
            sourceAppDao.upsert(sourceApp)
            /** 本次剪贴记录的综合搜索字段，由用户可搜索的信息拼接生成。 */
            val searchText = buildClipSearchText(captureEntity, sourceApp.appName)
            /** 本次捕获构造出的剪贴数据库实体，id=0 表示新增候选。 */
            val newClip = ClipData(
                id = 0,
                content = captureEntity.content,
                timestamp = captureEntity.timestamp,
                sourceAppPackage = sourcePackage,
                pinnedTime = 0,
                link = captureEntity.link,
                searchText = searchText
            )

            if (!captureEntity.link.isNullOrBlank()) {
                /** 本次捕获得到或待补全的链接预览缓存。 */
                val linkPreviewData = LinkPreviewData(
                    link = captureEntity.link,
                    title = captureEntity.linkTitle,
                    description = captureEntity.linkDescription,
                    imageUrl = captureEntity.linkImageUrl,
                    siteName = captureEntity.linkSiteName
                )
                linkPreviewDao.upsert(linkPreviewData)
            }

            /** 本次真实保存或更新后的剪贴记录 id。 */
            val clipId = when (saveAction) {
                DuplicateClipSaveAction.InsertNew -> insertNewClip(newClip, sourceApp.packageName)
                is DuplicateClipSaveAction.UpdateExisting -> {
                    /** 重复内容更新实体；保留用户状态并使用捕获时间刷新列表时间。 */
                    val clipToUpdate = buildDuplicateClipUpdate(
                        newClip = newClip,
                        existingClip = saveAction.existingClip.clip,
                        capturedAtMillis = captureEntity.timestamp
                    )
                    clipDao.upsertClip(clipToUpdate)
                    saveAction.existingClip.clip.id
                }
                is DuplicateClipSaveAction.SkipDuplicate -> error("addNewClip: skip action should return before writing")
            }
            AppSetting.markBackupDirty()
            ClipSaveResult.Saved(clipId)
        }
    }

    /**
     * 构建剪贴记录搜索字段。
     *
     * @param captureEntity 本次剪贴捕获实体，包含内容、来源和链接预览摘要。
     */
    private fun buildClipSearchText(
        captureEntity: ClipCaptureEntity,
        sourceAppName: String,
    ): String {
        return captureEntity.content
            .plus(captureEntity.link)
            .plus(sourceAppName)
            .plus(captureEntity.sourcePackage)
            .plus(captureEntity.linkTitle)
            .plus(captureEntity.linkDescription)
            .plus(captureEntity.linkSiteName)
    }

    /**
     * 插入全新的剪贴记录并返回主键。
     *
     * @param newClip 新剪贴数据库实体；调用方必须传入 id=0 的新增候选。
     * @param sourcePackage 来源包名，用于极端情况下 Upsert 返回非正 id 后回查。
     */
    private suspend fun insertNewClip(
        newClip: ClipData,
        sourcePackage: String,
    ): Long {
        /** Room upsert 对新增行返回的 rowId；非正值时需要回查确认。 */
        val rowId = clipDao.upsertClip(newClip.copy(id = 0))
        return when {
            rowId > 0L -> rowId
            else -> clipDao.loadClipDetail(newClip.content, sourcePackage)?.clip?.id
                ?: error("insertNewClip: upsertClip 后未找到记录 contentLength=${newClip.content.length} packageName=$sourcePackage")
        }
    }

    override suspend fun deleteClip(clip: ClipShowEntity) = withContext(Dispatchers.IO) {
        moveClipsToRecycleBin(setOf(clip.id)) > 0
    }

    override suspend fun moveClipsToRecycleBin(ids: Set<Long>): Int = withContext(Dispatchers.IO) {
        val normalizedIds = ids.filter { it > 0L }.distinct()
        if (normalizedIds.isEmpty()) return@withContext 0
        val deletedAt = System.currentTimeMillis()
        val moved = appDatabase.withTransaction {
            normalizedIds.chunked(ID_BATCH_SIZE).sumOf { chunk ->
                clipDao.moveClipsToRecycleBin(chunk, deletedAt)
            }
        }
        if (moved > 0) AppSetting.markBackupDirty()
        moved
    }

    override suspend fun deleteClipPermanently(clip: ClipShowEntity) = withContext(Dispatchers.IO) {
        deleteClipsPermanently(setOf(clip.id)) > 0
    }

    override suspend fun deleteClipsPermanently(ids: Set<Long>): Int = withContext(Dispatchers.IO) {
        val normalizedIds = ids.filter { it > 0L }.distinct()
        if (normalizedIds.isEmpty()) return@withContext 0
        val deleted = appDatabase.withTransaction {
            normalizedIds.chunked(ID_BATCH_SIZE).sumOf { chunk ->
                clipDao.deleteClipsByIds(chunk)
            }
        }
        if (deleted > 0) AppSetting.markBackupDirty()
        deleted
    }

    override suspend fun restoreClipsFromRecycleBin(ids: Set<Long>): Int = withContext(Dispatchers.IO) {
        val normalizedIds = ids.filter { it > 0L }.distinct()
        if (normalizedIds.isEmpty()) return@withContext 0
        val restored = appDatabase.withTransaction {
            normalizedIds.chunked(ID_BATCH_SIZE).sumOf { chunk ->
                clipDao.restoreClipsFromRecycleBin(chunk)
            }
        }
        if (restored > 0) AppSetting.markBackupDirty()
        restored
    }

    override fun loadRecycleBinClips(): PagingSource<Int, ClipDetail> {
        return clipDao.loadRecycleBinClips()
    }

    override suspend fun updatePinStatus(clipId: Long, isPinned: Boolean) {
        val pinnedTime = if (isPinned) System.currentTimeMillis() else 0L
        clipDao.updatePinStatus(clipId, pinnedTime)
        AppSetting.markBackupDirty()
    }

    override suspend fun updateFoldStatus(clipId: Long, isFolded: Boolean) = withContext(Dispatchers.IO) {
        val foldedAt = if (isFolded) System.currentTimeMillis() else 0L
        clipDao.updateFoldStatus(clipId, isFolded, foldedAt)
        AppSetting.markBackupDirty()
    }

    override suspend fun updateTimestamp(clipId: Long) {
        val currentTime = System.currentTimeMillis()
        // 这里直接调用 upsertClip 来更新 timestamp，保持逻辑一致性
        clipDao.updateTimestamp(clipId, currentTime)
        AppSetting.markBackupDirty()
    }

    override fun loadClips(visibilityScope: ClipVisibilityScope): PagingSource<Int, ClipDetail> {
        return clipDao.loadClipsByFoldState(visibilityScope.toFoldState())
    }

    override fun observeFoldedClipCount(): Flow<Int> {
        return clipDao.observeFoldedClipCount()
    }

    override fun observeRecycleBinCount(): Flow<Int> {
        return clipDao.observeRecycleBinCount()
    }

    override suspend fun clearRecycleBinPermanently(): Int = withContext(Dispatchers.IO) {
        val deleted = clipDao.clearRecycleBinPermanently()
        if (deleted > 0) AppSetting.markBackupDirty()
        deleted
    }

    override suspend fun cleanupExpiredRecycleBinClips(days: Int): Int = withContext(Dispatchers.IO) {
        val safeDays = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        val cutoffMillis = System.currentTimeMillis() - safeDays * 24L * 60L * 60L * 1_000L
        val deleted = clipDao.cleanupExpiredRecycleBinClips(cutoffMillis)
        if (deleted > 0) AppSetting.markBackupDirty()
        deleted
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        clipDao.clearAll()
        AppSetting.markBackupDirty()
    }

    override suspend fun loadSourceApp(packageName: String) = withContext(Dispatchers.IO) {
        sourceAppDao.loadByPackageName(packageName)
    }

    override suspend fun updateSourceAppIcon(
        packageName: String,
        appName: String?,
        iconPath: String,
        primaryColor: Int?,
        iconHash: String,
    ) = withContext(Dispatchers.IO) {
        /** 已存在的来源 App 缓存；补图只更新图标字段，名称为空时保留旧名称。 */
        val existingSourceApp = sourceAppDao.loadByPackageName(packageName)

        /** 图标补全后的来源 App 缓存；packageName 是唯一业务身份。 */
        val sourceApp = buildSourceAppIconUpdate(
            packageName = packageName,
            appName = appName,
            iconPath = iconPath,
            primaryColor = primaryColor,
            iconHash = iconHash,
            existingSourceApp = existingSourceApp
        )
        sourceAppDao.upsert(sourceApp)
        AppSetting.markBackupDirty()
    }

    override suspend fun clearSourceAppIconCache(packageName: String) = withContext(Dispatchers.IO) {
        /** 只有数据库里已存在对应来源缓存时才需要清理并标记备份 dirty。 */
        val clearedRows = sourceAppDao.clearIconCache(packageName)
        if (clearedRows > 0) {
            AppSetting.markBackupDirty()
        }
    }

    override fun loadAllSourceApps(): Flow<List<SourceAppData>> {
        return sourceAppDao.loadAllSourceApps()
    }

    override suspend fun loadLinkPreview(link: String) = withContext(Dispatchers.IO) {
        linkPreviewDao.loadByLink(link)
    }

    /**
     * 写入或补全链接预览缓存。
     *
     * 首次剪贴保存时可能只拿到域名兜底；后续 WebView 真实加载网页后会带来更完整的标题、描述或封面。
     * 这里合并新旧记录时优先保留已有非空字段，避免一次不完整的 DOM 扫描覆盖首轮已经解析成功的内容。
     */
    override suspend fun upsertLinkPreview(preview: LinkPreviewData) = withContext(Dispatchers.IO) {
        if (preview.link.isBlank()) return@withContext
        val old = linkPreviewDao.loadByLink(preview.link)
        val merged = LinkPreviewData(
            link = preview.link,
            title = old?.title.takeUnless { it.isNullOrBlank() } ?: preview.title,
            description = old?.description.takeUnless { it.isNullOrBlank() } ?: preview.description,
            imageUrl = old?.imageUrl.takeUnless { it.isNullOrBlank() } ?: preview.imageUrl,
            siteName = old?.siteName.takeUnless { it.isNullOrBlank() } ?: preview.siteName,
        )
        linkPreviewDao.upsert(merged)
        AppSetting.markBackupDirty()

        clipDao.loadClipDetailsByLink(preview.link).forEach { detail ->
            val clip = detail.clip
            val sourceApp = detail.sourceApp
            // search_text 是本地搜索唯一读取的综合索引；预览补齐后同步刷新，保证后续能搜到新标题/描述。
            val searchText = clip.content
                .plus(clip.link)
                .plus(sourceApp?.appName)
                .plus(sourceApp?.packageName)
                .plus(merged.title)
                .plus(merged.description)
                .plus(merged.siteName)
            clipDao.upsertClip(clip.copy(searchText = searchText))
        }
    }

    override suspend fun loadClipDetail(id: Long) = withContext(Dispatchers.IO) {
        clipDao.loadClipDetail(id)?.toUi()
    }

    override suspend fun loadLastClip() = withContext(Dispatchers.IO) {
        clipDao.loadLastClip()
    }
}

/**
 * 构建剪贴入库时的来源 App 缓存。
 *
 * @param captureEntity 本次剪贴捕获实体，Provider 异步模式下图标字段可能为空。
 * @param existingSourceApp 数据库中已有的同包名来源 App，用于保留已补齐的图标字段。
 */
internal fun buildSourceAppForClip(
    captureEntity: ClipCaptureEntity,
    existingSourceApp: SourceAppData?,
): SourceAppData {
    /** 写入来源 App 的名称；本次名称不可信时保留已有真实名称，避免 Unknown 覆盖历史明确来源。 */
    val mergedAppName = when {
        !isUnknownClipSource(captureEntity.sourcePackage, captureEntity.sourceAppName) -> captureEntity.sourceAppName
        existingSourceApp != null && !isUnknownClipSource(existingSourceApp.packageName, existingSourceApp.appName) -> existingSourceApp.appName
        else -> captureEntity.sourceAppName
    }

    return SourceAppData(
        packageName = captureEntity.sourcePackage,
        appName = mergedAppName,
        iconPath = captureEntity.sourceAppIconPath ?: existingSourceApp?.iconPath,
        primaryColor = captureEntity.sourcePrimaryColor ?: existingSourceApp?.primaryColor,
        iconHash = captureEntity.sourceAppIconHash ?: existingSourceApp?.iconHash
    )
}

/**
 * 同内容剪贴入库决策。
 *
 * 决策本身不执行数据库写入，便于单元测试覆盖来源未知、同包名和跨 App 新增等规则。
 */
internal sealed interface DuplicateClipSaveAction {
    /** 新增一条剪贴记录。 */
    data object InsertNew : DuplicateClipSaveAction

    /**
     * 更新已有剪贴记录。
     *
     * @param existingClip 被选中的旧记录；调用方需要保留其用户状态并刷新来源/时间/searchText。
     */
    data class UpdateExisting(
        /** 被选中的已有剪贴记录详情。 */
        val existingClip: ClipDetail,
    ) : DuplicateClipSaveAction

    /**
     * 跳过本次重复保存。
     *
     * @param existingClip 已有明确来源记录；本次未知来源不能覆盖它，也不应新增未知重复记录。
     */
    data class SkipDuplicate(
        /** 被重复规则命中的已有剪贴记录详情。 */
        val existingClip: ClipDetail,
    ) : DuplicateClipSaveAction
}

/**
 * 决定同内容剪贴应该更新、插入还是跳过。
 *
 * @param candidates 与本次内容相同且未进入回收站的已有候选。
 * @param incomingSourcePackage 本次来源包名，空字符串代表未知来源。
 * @param incomingSourceAppName 本次来源应用名，空字符串、Unknown 或“未知”代表不可信名称。
 */
internal fun decideDuplicateClipSaveAction(
    candidates: List<ClipDetail>,
    incomingSourcePackage: String?,
    incomingSourceAppName: String?,
): DuplicateClipSaveAction {
    /** 本次来源包名的可比较形式；为空表示本次来源未知。 */
    val incomingPackage = incomingSourcePackage.normalizedClipSourcePackage()
    /** 本次来源是否未知；未知来源遇到明确来源候选时按重复跳过。 */
    val incomingSourceUnknown = isUnknownClipSource(incomingSourcePackage, incomingSourceAppName)

    /** 同包名候选优先，保证链接预览二次保存和同 App 重复复制更新同一条记录。 */
    val samePackageCandidate = incomingPackage?.let { packageName ->
        candidates.firstOrNull { candidate ->
            candidate.clip.sourceAppPackage.normalizedClipSourcePackage() == packageName
        }
    }
    if (samePackageCandidate != null) {
        return DuplicateClipSaveAction.UpdateExisting(samePackageCandidate)
    }

    /** 已有明确来源候选；本次未知来源不新增重复记录，也不覆盖明确来源。 */
    val knownSourceCandidate = candidates.firstOrNull { candidate ->
        !isUnknownClipSource(candidate.clip.sourceAppPackage, candidate.sourceApp?.appName)
    }
    if (incomingSourceUnknown && knownSourceCandidate != null) {
        return DuplicateClipSaveAction.SkipDuplicate(knownSourceCandidate)
    }

    /** 同内容下第一个未知来源候选；本次来源明确时承接来源升级，本次也未知时承接链接预览二次补全。 */
    val unknownSourceCandidate = candidates.firstOrNull { candidate ->
        isUnknownClipSource(candidate.clip.sourceAppPackage, candidate.sourceApp?.appName)
    }
    if (unknownSourceCandidate != null) {
        return DuplicateClipSaveAction.UpdateExisting(unknownSourceCandidate)
    }

    return DuplicateClipSaveAction.InsertNew
}

/**
 * 构建图标补全时的来源 App 更新数据。
 *
 * @param packageName 来源应用包名。
 * @param appName 来源应用名称；为空时保留旧名称，旧名称也不存在时回退到包名。
 * @param iconPath 已保存成功的来源图标路径。
 * @param primaryColor 已保存图标提取出的主色，可能为空。
 * @param iconHash 已保存图标的 Bitmap.toStableHash()，代表数据库语义上的图标签名。
 * @param existingSourceApp 数据库中已有的同包名来源 App。
 */
internal fun buildSourceAppIconUpdate(
    packageName: String,
    appName: String?,
    iconPath: String,
    primaryColor: Int?,
    iconHash: String,
    existingSourceApp: SourceAppData?,
): SourceAppData {
    /** 写入来源 App 的展示名；优先使用本次名称，避免空名称覆盖历史可读名称。 */
    val mergedAppName = appName?.takeIf { it.isNotBlank() }
        ?: existingSourceApp?.appName
        ?: packageName

    return SourceAppData(
        packageName = packageName,
        appName = mergedAppName,
        iconPath = iconPath,
        primaryColor = primaryColor,
        iconHash = iconHash
    )
}

/**
 * 构建重复剪贴内容的更新实体。
 *
 * @param newClip 本次捕获构造出的新剪贴实体，承载最新内容、来源和搜索字段。
 * @param existingClip 数据库中已有的同内容同来源剪贴实体，用于保留用户状态。
 * @param capturedAtMillis Shizuku 或主进程捕获剪贴板时记录的时间，避免并发提交完成顺序影响列表顺序。
 */
internal fun buildDuplicateClipUpdate(
    newClip: ClipData,
    existingClip: ClipData,
    capturedAtMillis: Long,
): ClipData {
    return newClip.copy(
        id = existingClip.id,
        pinnedTime = existingClip.pinnedTime,
        isFolded = existingClip.isFolded,
        foldedAt = existingClip.foldedAt,
        timestamp = capturedAtMillis
    )
}
