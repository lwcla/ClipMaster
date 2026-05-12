package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import com.cla.clip.base.general.dao.ClipDao
import com.cla.clip.base.general.dao.ClipData
import com.cla.clip.base.general.dao.LinkPreviewDao
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SourceAppDao
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipShowEntity
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
    private val clipDao: ClipDao,
    private val sourceAppDao: SourceAppDao,
    private val linkPreviewDao: LinkPreviewDao,
) : ClipRepository {

    companion object {
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
        sourceAppPackage: String?,
    ): PagingSource<Int, ClipDetail> {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) {
            return clipDao.searchClipsByFilters(
                startTime = startTime,
                endTime = endTime,
                sourceAppPackage = sourceAppPackage
            )
        }

        val ftsQuery = buildFtsQuery(trimmed)
        if (ftsQuery.isBlank()) {
            return clipDao.searchClipsByLike(
                keyword = trimmed,
                startTime = startTime,
                endTime = endTime,
                sourceAppPackage = sourceAppPackage
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
            sourceAppPackage = sourceAppPackage
        )
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

    override suspend fun addNewClip(captureEntity: ClipCaptureEntity) = withContext(Dispatchers.IO) {
        // 拼接所有可能需要搜索的内容，用来后续做模糊搜索
        val searchText = captureEntity.content
            .plus(captureEntity.link)
            .plus(captureEntity.sourceAppName)
            .plus(captureEntity.sourcePackage)
            .plus(captureEntity.linkTitle)
            .plus(captureEntity.linkDescription)
            .plus(captureEntity.linkSiteName)

        // 1. 在这里转成 DB 实体
        val newClip = ClipData(
            id = 0, // 只有 Repository 知道新建时这里填 0
            content = captureEntity.content,
            timestamp = captureEntity.timestamp,
            sourceAppPackage = captureEntity.sourcePackage,
            pinnedTime = 0,
            link = captureEntity.link,
            searchText = searchText
        )

        if (!captureEntity.link.isNullOrBlank()) {
            val linkPreviewData = LinkPreviewData(
                link = captureEntity.link,
                title = captureEntity.linkTitle,
                description = captureEntity.linkDescription,
                imageUrl = captureEntity.linkImageUrl,
                siteName = captureEntity.linkSiteName
            )
            linkPreviewDao.upsert(linkPreviewData)
        }

        val sourceApp = SourceAppData(
            packageName = captureEntity.sourcePackage,
            appName = captureEntity.sourceAppName,
            iconPath = captureEntity.sourceAppIconPath,
            primaryColor = captureEntity.sourcePrimaryColor,
            iconHash = captureEntity.sourceAppIconHash
        )

        sourceAppDao.upsert(sourceApp)

        // 先根据content尝试查找旧数据
        val existingClip = clipDao.loadClipDetail(newClip.content, sourceApp.packageName)
        if (existingClip != null) {
            // === 情况 A：数据库有相同 content ===
            // 使用 newClip 的所有数据，但覆盖回旧数据的 id 和 groupId
            val clipToUpdate = newClip.copy(
                id = existingClip.clip.id,
                pinnedTime = existingClip.clip.pinnedTime,
                timestamp = System.currentTimeMillis() // 更新时间戳，表示这是最新的一次复制
            )
            // 执行更新
            clipDao.upsertClip(clipToUpdate)
            return@withContext existingClip.clip.id
        } else {
            // 插入数据
            val rowId = clipDao.upsertClip(newClip.copy(id = 0))
            // 关键：如果是更新旧任务，直接返回旧 id
            return@withContext when {
                rowId > 0L -> rowId
                else -> clipDao.loadClipDetail(newClip.content, sourceApp.packageName)?.clip?.id
                    ?: error("addNewClip: upsertClip 后未找到任务 newClip=$newClip")
            }
        }
    }

    override suspend fun deleteClip(clip: ClipShowEntity) = withContext(Dispatchers.IO) {
        clipDao.deleteClipById(clip.id) > 0
    }

    override suspend fun updatePinStatus(clipId: Long, isPinned: Boolean) {
        val pinnedTime = if (isPinned) System.currentTimeMillis() else 0L
        clipDao.updatePinStatus(clipId, pinnedTime)
    }

    override suspend fun updateTimestamp(clipId: Long) {
        val currentTime = System.currentTimeMillis()
        // 这里直接调用 upsertClip 来更新 timestamp，保持逻辑一致性
        clipDao.updateTimestamp(clipId, currentTime)
    }

    override fun loadAllClips(): PagingSource<Int, ClipDetail> {
        return clipDao.loadAllClips()
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        clipDao.clearAll()
    }

    override suspend fun loadSourceApp(packageName: String) = withContext(Dispatchers.IO) {
        sourceAppDao.loadByPackageName(packageName)
    }

    override fun loadAllSourceApps(): Flow<List<SourceAppData>> {
        return sourceAppDao.loadAllSourceApps()
    }

    override suspend fun loadLinkPreview(link: String) = withContext(Dispatchers.IO) {
        linkPreviewDao.loadByLink(link)
    }

    override suspend fun loadClipDetail(id: Long) = withContext(Dispatchers.IO) {
        clipDao.loadClipDetail(id)?.toUi()
    }

    override suspend fun loadLastClip() = withContext(Dispatchers.IO) {
        clipDao.loadLastClip()
    }
}
