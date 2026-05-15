package com.cla.clip.base.general.repository

import androidx.paging.PagingSource
import com.cla.clip.base.general.dao.LinkPreviewData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.dao.data.ClipDetail
import com.cla.clip.base.general.dao.data.LastClipData
import com.cla.clip.base.general.entity.ClipCaptureEntity
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import kotlinx.coroutines.flow.Flow

/**
 * 剪贴板数据仓库的接口。
 * 定义了所有与Clip数据相关的操作，作为数据源和业务逻辑之间的契约。
 */
interface ClipRepository {

    /**
     * 根据查询词搜索所有剪贴板条目（包括历史记录）。
     */
    fun searchAllClips(userInput: String): Flow<List<ClipShowEntity>>

    /**
     * 按关键词、时间范围和来源 App 分页搜索剪贴记录。
     *
     * Repository 负责把用户输入和来源 App 多选集合转换成 DAO 可执行的查询参数，
     * 避免 UI 层理解 FTS 语法、Room `IN` 查询或空集合边界。
     */
    fun searchClips(
        userInput: String,
        startTime: Long?,
        endTime: Long?,
        sourceAppPackages: Set<String>,
        visibilityScope: ClipVisibilityScope,
    ): PagingSource<Int, ClipDetail>

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

    /** 更新折叠状态；折叠数据会从普通列表/普通搜索隐藏，只在折叠范围展示。 */
    suspend fun updateFoldStatus(clipId: Long, isFolded: Boolean)

    /** 更新时间戳 */
    suspend fun updateTimestamp(clipId: Long)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用，返回一个 PagingSource 对象。
     * 排序规则：置顶在前，其余按时间倒序。
     */
    fun loadClips(visibilityScope: ClipVisibilityScope): PagingSource<Int, ClipDetail>

    /** 观察折叠记录数量，供入口展示；实现层应使用轻量 COUNT 查询。 */
    fun observeFoldedClipCount(): Flow<Int>

    /** 清空所有剪贴板数据。 */
    suspend fun clearAll()

    /** 根据包名和剪贴板内容获取来源app信息 */
    suspend fun loadSourceApp(packageName: String): SourceAppData?

    /** 加载所有来源 App，供搜索筛选器展示。 */
    fun loadAllSourceApps(): Flow<List<SourceAppData>>

    /** 根据链接查找历史数据 */
    suspend fun loadLinkPreview(link: String): LinkPreviewData?

    /**
     * 写入或补全链接预览缓存。
     *
     * WebView 提取页会在真实页面加载后调用这里补齐首轮 HTTP 解析拿不到的标题或封面；实现层需要保留旧记录中的非空字段，
     * 只用新获得的非空字段填补空缺，并同步刷新关联剪贴记录的搜索索引。
     */
    suspend fun upsertLinkPreview(preview: LinkPreviewData)

    /** 根据id查找剪贴数据 */
    suspend fun loadClipDetail(id: Long): ClipShowEntity?

    /** 获取最后保存的剪贴数据 */
    suspend fun loadLastClip(): LastClipData?
}
