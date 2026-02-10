package com.cla.clip.base.general.repository

import com.cla.clip.base.general.entity.ClipData
import kotlinx.coroutines.flow.Flow

/**
 * 剪贴板数据仓库的接口。
 * 定义了所有与Clip数据相关的操作，作为数据源和业务逻辑之间的契约。
 */
interface ClipRepository {

    /**
     * 获取主屏幕上显示的、未置顶的最新剪贴板条目。
     */
    fun getLatestClips(): Flow<List<ClipData>>

    /**
     * 获取所有置顶的最新剪贴板条目。
     */
    fun getPinnedClips(): Flow<List<ClipData>>

    /**
     * 根据查询词搜索所有剪贴板条目（包括历史记录）。
     */
    fun searchAllClips(query: String): Flow<List<ClipData>>

    /**
     * 根据分组ID获取一个条目的所有历史版本。
     */
    suspend fun getHistoryForGroup(groupId: Long): List<ClipData>

    /**
     * 新增一个全新的剪贴板条目。
     */
    suspend fun addNewClip(clip: ClipData)

    /**
     * 为一个现有的剪贴板条目创建一个新的编辑历史版本。
     */
    suspend fun createNewVersionForClip(newVersionClip: ClipData)

    /**
     * 更新或插入单个Clip条目。主要用于置顶、修改颜色等单一属性的更新。
     */
    suspend fun upsertClip(clip: ClipData): Long

    /**
     * 删除一个分组下的所有历史记录。
     */
    suspend fun deleteClipGroup(groupId: Long)

    /**
     * 清空所有剪贴板数据。
     */
    suspend fun clearAll()

    /** 获取最新的剪贴板条目（无论是否置顶）。这个方法主要用于监听剪贴板变化时，快速获取最新内容以进行去重或预处理。 */
    suspend fun getLatestClip(): ClipData?
}