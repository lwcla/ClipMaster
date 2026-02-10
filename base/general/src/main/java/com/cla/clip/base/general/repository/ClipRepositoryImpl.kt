package com.cla.clip.base.general.repository

import com.cla.clip.base.general.dao.ClipDao
import com.cla.clip.base.general.entity.ClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ClipRepository的默认实现。
 * 通过构造函数注入ClipDao，并委托所有数据操作给它。
 * 使用 @Inject constructor() 使Hilt能够创建这个类的实例。
 */
class ClipRepositoryImpl @Inject constructor(
    private val clipDao: ClipDao
) : ClipRepository {

    // 使用 withContext(Dispatchers.IO) 确保所有数据库写操作都在IO线程上执行。
    // Flow 本身是异步的，Room会自动处理其线程，所以读操作不需要显式切换。

    override fun getLatestClips(): Flow<List<ClipData>> {
        return clipDao.getLatestClips()
    }

    override fun getPinnedClips(): Flow<List<ClipData>> {
        return clipDao.getPinnedClips()
    }

    override fun searchAllClips(query: String): Flow<List<ClipData>> {
        return clipDao.searchAllClips(query)
    }

    override suspend fun getHistoryForGroup(groupId: Long): List<ClipData> = withContext(Dispatchers.IO) {
        clipDao.getHistoryForGroup(groupId)
    }

    override suspend fun addNewClip(clip: ClipData) = withContext(Dispatchers.IO) {
        clipDao.addNewClip(clip)
    }

    override suspend fun createNewVersionForClip(newVersionClip: ClipData) = withContext(Dispatchers.IO) {
        clipDao.createNewVersionForClip(newVersionClip)
    }

    override suspend fun upsertClip(clip: ClipData) = withContext(Dispatchers.IO) {
        clipDao.upsertClip(clip)
    }

    override suspend fun deleteClipGroup(groupId: Long) = withContext(Dispatchers.IO) {
        clipDao.deleteClipGroup(groupId)
    }

    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        clipDao.clearAll()
    }

    override suspend fun getLatestClip(): ClipData? = withContext(Dispatchers.IO) {
        clipDao.getLatestClip()
    }
}