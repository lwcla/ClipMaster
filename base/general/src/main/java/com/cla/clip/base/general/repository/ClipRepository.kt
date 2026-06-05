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
     * Repository 负责把用户输入、来源 App 多选集合和搜索范围转换成 DAO 可执行的查询参数；
     * 普通搜索时间筛选使用剪贴时间，折叠搜索时间筛选使用折叠时间，避免 UI 层理解底层字段差异。
     */
    fun searchClips(
        userInput: String,
        startTime: Long?,
        endTime: Long?,
        sourceAppPackages: Set<String>,
        visibilityScope: ClipVisibilityScope,
    ): PagingSource<Int, ClipDetail>

    /**
     * 保存一个新的剪贴板条目或更新已有条目。
     *
     * 返回值会区分真实写库和重复跳过，调用方据此决定是否发送通知和调度备份。
     */
    suspend fun addNewClip(captureEntity: ClipCaptureEntity): ClipSaveResult

    /** 将一个剪贴板内容移入回收站。 */
    suspend fun deleteClip(clip: ClipShowEntity): Boolean

    /** 将指定剪贴记录移入回收站；同一批次使用统一删除时间，保证回收站排序稳定。 */
    suspend fun moveClipsToRecycleBin(ids: Set<Long>): Int

    /** 永久删除单条剪贴记录，不进入回收站。 */
    suspend fun deleteClipPermanently(clip: ClipShowEntity): Boolean

    /** 永久删除指定剪贴记录；调用方可传入正常数据或回收站数据 id，已不存在的 id 会被忽略。 */
    suspend fun deleteClipsPermanently(ids: Set<Long>): Int

    /** 从回收站恢复指定剪贴记录；只清除删除时间，不改变折叠、折叠时间、置顶和原始时间。 */
    suspend fun restoreClipsFromRecycleBin(ids: Set<Long>): Int

    /** 分页加载回收站剪贴记录，排序为删除时间倒序。 */
    fun loadRecycleBinClips(): PagingSource<Int, ClipDetail>

    /** 更新置顶状态 */
    suspend fun updatePinStatus(clipId: Long, isPinned: Boolean)

    /** 更新折叠状态；折叠时记录折叠时间，取消折叠时清空折叠时间。 */
    suspend fun updateFoldStatus(clipId: Long, isFolded: Boolean)

    /** 更新时间戳 */
    suspend fun updateTimestamp(clipId: Long)

    /**
     * 加载所有的剪贴板数据，供分页使用。这个方法会被 PagingSource 调用，返回一个 PagingSource 对象。
     * 排序规则：普通范围置顶在前，其余按剪贴时间倒序；折叠范围也置顶在前，组内按折叠时间倒序。
     */
    fun loadClips(visibilityScope: ClipVisibilityScope): PagingSource<Int, ClipDetail>

    /** 观察折叠记录数量，供入口展示；实现层应使用轻量 COUNT 查询。 */
    fun observeFoldedClipCount(): Flow<Int>

    /** 观察回收站记录数量，供“我的”入口展示；实现层应使用轻量 COUNT 查询。 */
    fun observeRecycleBinCount(): Flow<Int>

    /** 永久清空回收站，返回实际删除的记录数。 */
    suspend fun clearRecycleBinPermanently(): Int

    /** 按保留天数清理过期回收站记录；days 按滚动 24 小时窗口计算。 */
    suspend fun cleanupExpiredRecycleBinClips(days: Int): Int

    /** 清空所有剪贴板数据。 */
    suspend fun clearAll()

    /** 根据包名和剪贴板内容获取来源app信息 */
    suspend fun loadSourceApp(packageName: String): SourceAppData?

    /**
     * 更新来源 App 图标缓存。
     *
     * 图标异步补全成功后调用；实现层需要只更新同包名来源 App，并标记备份 dirty。
     */
    suspend fun updateSourceAppIcon(
        packageName: String,
        appName: String?,
        iconPath: String,
        primaryColor: Int?,
        iconHash: String,
    )

    /** 清空来源 App 已失效的图标缓存，保留展示名称和主键。 */
    suspend fun clearSourceAppIconCache(packageName: String)

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

/** 剪贴保存结果，用于区分真实写库和按重复规则跳过。 */
sealed interface ClipSaveResult {
    /** 已新增或更新剪贴记录；clipId 是可进入详情页的稳定记录 id。 */
    data class Saved(
        /** 保存或更新后的剪贴记录 id。 */
        val clipId: Long,
    ) : ClipSaveResult

    /** 本次内容按重复规则跳过；clipId 是被判定为重复的候选记录 id，可能为空。 */
    data class SkippedDuplicate(
        /** 被重复规则命中的已有剪贴记录 id；没有具体候选时为空。 */
        val clipId: Long?,
    ) : ClipSaveResult
}
