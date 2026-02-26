package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import com.cla.clip.base.general.dao.ClipDao
import com.cla.clip.base.general.dao.SourceAppDao
import com.cla.clip.base.general.dao.data.ClipData
import com.cla.clip.base.general.dao.data.ClipWithSourceApp
import com.cla.clip.base.general.dao.data.SourceApp
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipEntity
import com.cla.clip.base.general.entity.toUi
import kotlinx.coroutines.Dispatchers
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
    private val sourceAppDao: SourceAppDao
) : ClipRepository {

    // 使用 withContext(Dispatchers.IO) 确保所有数据库写操作都在IO线程上执行。
    // Flow 本身是异步的，Room会自动处理其线程，所以读操作不需要显式切换。

    override fun searchAllClips(query: String) = clipDao.searchAllClips(query).map { it.toUi() }

    override suspend fun addNewClip(captureEntity: ClipCaptureEntity) = withContext(Dispatchers.IO) {
        // 1. 在这里转成 DB 实体
        val newClip = ClipData(
            id = 0, // 只有 Repository 知道新建时这里填 0
            content = captureEntity.content,
            timestamp = captureEntity.timestamp,
            sourceAppPackage = captureEntity.sourcePackage,
            pinnedTime = 0,
            linkTitle = captureEntity.linkTitle,
            linkDescription = captureEntity.linkDescription,
            linkImageUrl = captureEntity.linkImageUrl,
            linkSiteName = captureEntity.linkSiteName,
        )

        val sourceApp = SourceApp(
            packageName = captureEntity.sourcePackage,
            appName = captureEntity.sourceAppName,
            iconPath = captureEntity.sourceAppIconPath,
            primaryColor = captureEntity.sourcePrimaryColor
        )

        sourceAppDao.upsertSourceApp(sourceApp)

        // 先根据content尝试查找旧数据
        val existingClip = clipDao.getClipByContent(newClip.content)
        if (existingClip != null) {
            // === 情况 A：数据库有相同 content ===
            // 使用 newClip 的所有数据，但覆盖回旧数据的 id 和 groupId
            val clipToUpdate = newClip.copy(
                id = existingClip.id,
                pinnedTime = existingClip.pinnedTime,
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

    override fun loadAllClips(): PagingSource<Int, ClipWithSourceApp> {
        return clipDao.loadAllClips()
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        clipDao.clearAll()
    }

    override suspend fun getLatestClip() = withContext(Dispatchers.IO) { clipDao.getLatestClip()?.toUi() }
}