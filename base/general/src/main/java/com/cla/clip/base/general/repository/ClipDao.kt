package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipShowEntity
import kotlinx.coroutines.flow.Flow

/**
 * 剪贴板数据仓库的接口。
 * 定义了所有与Clip数据相关的操作，作为数据源和业务逻辑之间的契约。
 */
interface ClipDao {

    /**
     * 根据查询词搜索所有剪贴板条目（包括历史记录）。
     */
    fun searchAllClips(userInput: String): Flow<List<ClipShowEntity>>

    /**
     * 新增一个全新的剪贴板条目。
     */
    suspend fun addNewClip(captureEntity: ClipCaptureEntity): Long

    /**
     * 删除一个剪贴板内容
     *
     * @param clip
     */
    suspend fun deleteClip(clip: ClipShowEntity): Boolean

    /** 更新置顶状态 */
    suspend fun updatePinStatus(clipId: Long, isPinned: Boolean)

    /** 更新时间戳 */
    suspend fun updateTimestamp(clipId: Long)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用，返回一个 PagingSource 对象。
     * 排序规则：置顶在前，其余按时间倒序。
     */
    fun loadAllClips(): PagingSource<Int, ClipDetail>

    /** 清空所有剪贴板数据。 */
    suspend fun clearAll()

    /** 根据包名和剪贴板内容获取来源app信息 */
    suspend fun loadSourceApp(packageName: String): SourceAppData?

    /** 根据链接查找历史数据 */
    suspend fun loadLinkPreview(link: String): LinkPreviewData?

    /** 根据id查找剪贴数据 */
    suspend fun loadClipDetail(id: Long): ClipShowEntity?

    /** 获取最后保存的剪贴数据 */
    suspend fun loadLastClip(): String?
}