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
import com.cla.clip.base.general.entity.ClipEntity
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
class ClipDaoImpl @Inject constructor(
    private val clipDao: ClipDao,
    private val sourceAppDao: SourceAppDao,
    private val linkPreviewDao: LinkPreviewDao,
) : com.cla.clip.base.general.repository.ClipDao {

    // 使用 withContext(Dispatchers.IO) 确保所有数据库写操作都在IO线程上执行。
    // Flow 本身是异步的，Room会自动处理其线程，所以读操作不需要显式切换。
    override fun searchAllClips(userInput: String): Flow<List<ClipEntity>> {
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
        val existingClip = clipDao.loadClipWithSourceByContent(newClip.content, sourceApp.packageName)
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
        } else {
            // 插入数据
            clipDao.upsertClip(newClip.copy(id = 0))
        }
    }

    override suspend fun deleteClip(clip: ClipEntity) {
        clipDao.deleteClipById(clip.id)
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

    override suspend fun loadSourceAppByPackageName(packageName: String) = withContext(Dispatchers.IO) {
        sourceAppDao.loadByPackageName(packageName)
    }

    override suspend fun loadLinkPreviewByLink(link: String) = withContext(Dispatchers.IO) {
        linkPreviewDao.loadByLink(link)
    }
}