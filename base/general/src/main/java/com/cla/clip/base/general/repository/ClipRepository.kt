package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import com.cla.clip.base.general.dao.data.ClipData
import com.cla.clip.base.general.dao.data.ClipWithSourceApp
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipEntity
import kotlinx.coroutines.flow.Flow

/**
 * 剪贴板数据仓库的接口。
 * 定义了所有与Clip数据相关的操作，作为数据源和业务逻辑之间的契约。
 */
interface ClipRepository {

    /**
     * 根据查询词搜索所有剪贴板条目（包括历史记录）。
     */
    fun searchAllClips(query: String): Flow<List<ClipEntity>>

    /**
     * 新增一个全新的剪贴板条目。
     */
    suspend fun addNewClip(captureEntity: ClipCaptureEntity): Long

    /**
     * 删除一个剪贴板内容
     *
     * @param clip
     */
    suspend fun deleteClip(clip: ClipEntity)

    /** 更新置顶状态 */
    suspend fun updatePinStatus(clipId: Long, isPinned: Boolean)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用，返回一个 PagingSource 对象。
     * 排序规则：置顶在前，其余按时间倒序。
     */
    fun loadAllClips(): PagingSource<Int, ClipWithSourceApp>

    /**
     * 清空所有剪贴板数据。
     */
    suspend fun clearAll()

    /** 获取最新的剪贴板条目（无论是否置顶）。这个方法主要用于监听剪贴板变化时，快速获取最新内容以进行去重或预处理。 */
    suspend fun getLatestClip(): ClipEntity?
}