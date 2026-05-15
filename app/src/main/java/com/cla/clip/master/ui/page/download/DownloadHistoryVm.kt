package com.cla.clip.master.ui.page.download

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.DownloadTaskData
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageHistoryFileRef
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.DownloadRepository
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.normalizeImageOutputDir
import com.cla.clip.master.work.DownloadImagesWorker
import com.cla.clip.master.work.DownloadVideoWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 下载记录页日志标签，用于定位历史映射、重新下载和删除结果。 */
private const val TAG = "DownloadHistoryVm"

/** 内容 URI scheme 常量；删除和播放时用它区分 MediaStore URI 与旧系统文件路径。 */
private const val URI_SCHEME_CONTENT = "content"

/** 视频首帧缩略图最大边长，限制内存占用，避免历史列表一次性持有原始尺寸帧。 */
private const val VIDEO_THUMB_MAX_EDGE = 320

/** 下载记录分页每页大小；列表卡片会读取媒体元信息，页大小过大容易拖慢首屏。 */
private const val HISTORY_PAGE_SIZE = 20

/** MediaStore IN 查询分块大小，低于常见 999 参数上限，给其它 selection 参数留出余量。 */
private const val MEDIASTORE_QUERY_CHUNK_SIZE = 600

/** 图片批量下载在旧系统真实保存的父目录名称；需与 FileUtils 中保存实现保持一致。 */
private const val LEGACY_IMAGE_PARENT_DIR = "clipMaster"

/**
 * 下载记录页 Tab。
 *
 * 页面只在视频和图片两类历史之间切换；选中状态只对当前 Tab 生效，切换时会清空选择，避免误删另一类记录。
 */
enum class DownloadHistoryTab {
    /** 视频下载任务历史，对应 `download_tasks` 表。 */
    VIDEO,

    /** 图片批量下载历史，对应 `image_extract_batches` 和其级联图片项。 */
    IMAGE
}

/**
 * 下载记录页整体 UI 状态。
 *
 * ViewModel 将数据库记录、媒体可读性、选中态和操作提示聚合成这个对象，页面只做展示和用户事件分发。
 */
data class DownloadHistoryUiState(
    /** 当前选中的分类 Tab。 */
    val selectedTab: DownloadHistoryTab = DownloadHistoryTab.VIDEO,

    /** 视频历史总数，只通过 COUNT 查询获得，用于标题栏动作和清空确认数量。 */
    val videoCount: Int = 0,

    /** 图片批次历史总数，只通过 COUNT 查询获得，用于标题栏动作和清空确认数量。 */
    val imageCount: Int = 0,

    /** 仍在下载或合并的视频记录数量，用于清空确认时提示会停止后台任务。 */
    val videoRunningCount: Int = 0,

    /** 仍在下载的图片批次数量，用于清空确认时提示会停止后台任务。 */
    val imageRunningCount: Int = 0,

    /** 当前 Tab 是否处于多选管理态。 */
    val selectionMode: Boolean = false,

    /** 当前 Tab 选中的记录 id 集合。 */
    val selectedIds: Set<Long> = emptySet(),

    /** 当前选中记录里是否包含后台运行任务；用轻量 COUNT 查询计算，避免依赖已分页加载的可见卡片。 */
    val selectedHasRunning: Boolean = false,

    /** 删除或重新下载等后台操作是否正在执行。 */
    val busy: Boolean = false,

    /** 一次性结果提示文案；页面展示后应调用 clearMessage 清空，避免旋转重组重复提示。 */
    val message: String? = null,
)

/**
 * 视频下载历史展示模型。
 *
 * 该模型不直接暴露 Room 实体，避免 UI 依赖数据库字段细节；媒体元信息来自本地 URI/路径实时读取，读取失败会标记为已删除。
 */
data class DownloadHistoryVideoItem(
    /** 视频下载任务 id，也是重新下载、删除和进入下载页的导航主键。 */
    val id: Long,

    /** 用户可见标题，来源于下载任务保存的文件基础名。 */
    val title: String,

    /** 原始下载任务状态，页面会映射为本地化展示文案。 */
    val status: String,

    /** 下载或合并进度百分比，下载中状态用于展示。 */
    val progress: Int,

    /** 最近失败原因，失败态展示时使用。 */
    val errorMsg: String?,

    /** 最近更新时间，单位毫秒，用于相对时间展示。 */
    val updateTime: Long,

    /** 当前记录关联的本地媒体 URI 或旧系统路径；为空说明任务尚未创建输出目标。 */
    val localPath: String?,

    /** 本地媒体是否仍可读取；成功记录不可读时页面展示已删除状态。 */
    val localExists: Boolean,

    /** 视频文件大小，单位字节；无法读取时为空。 */
    val sizeBytes: Long?,

    /** 视频时长，单位毫秒；无法读取时为空。 */
    val durationMs: Long?,

    /** 本地首帧缩略图；只有本地媒体可读时才尽力生成，失败时为空并展示占位。 */
    val thumbnail: Bitmap?,
) {
    /** 任务是否仍在后台下载或合并，用于删除确认提示和列表状态展示。 */
    val running: Boolean
        get() = status == DownloadTaskData.STATUS_DOWNLOADING || status == DownloadTaskData.STATUS_MERGING

    /** 成功记录但本地文件不可读时认为本地文件已删除。 */
    val deletedLocal: Boolean
        get() = status == DownloadTaskData.STATUS_SUCCESS && !localExists
}

/**
 * 图片批量下载历史展示模型。
 *
 * 每个批次保留自己的输出目录和成功图片 URI，页面只展示前若干张缩略图；点击缩略图预览当前单张图片。
 */
data class DownloadHistoryImageBatch(
    /** 图片下载批次 id，也是重新下载、删除和 Worker 取消的主键。 */
    val id: Long,

    /** 批次标题，通常来自网页标题或剪贴板内容。 */
    val title: String,

    /** 批次状态，页面映射为本地化展示文案。 */
    val status: String,

    /** 本批次需要处理的图片总数。 */
    val totalCount: Int,

    /** 已成功保存的图片数量。 */
    val successCount: Int,

    /** 下载或发布失败数量。 */
    val failedCount: Int,

    /** 主动过滤的无效图片数量。 */
    val filteredCount: Int,

    /** 批次输出目录展示值；Android 10+ 只是辅助展示，不作为删除身份。 */
    val outputDir: String?,

    /** 最近更新时间，单位毫秒，用于相对时间展示。 */
    val updateTime: Long,

    /** 可读取的成功图片引用列表；Android 10+ 通常是 content URI，旧系统可能是真实文件路径。 */
    val imageUris: List<String>,
) {
    /** 批次是否还在下载，用于删除前先取消对应 Worker。 */
    val running: Boolean
        get() = status == ImageExtractBatchData.STATUS_DOWNLOADING

    /** 有成功计数的终态批次没有任何可读图片时，说明本地图片可能已被删除或旧记录缺少可靠路径。 */
    val deletedLocal: Boolean
        get() = shouldCheckLocalImages && imageUris.isEmpty()

    /** 成功计数里当前不可读取的图片数量；下载中或无成功文件的终态不做本地删除误判。 */
    val unreadableCount: Int
        get() = if (shouldCheckLocalImages) (successCount - imageUris.size).coerceAtLeast(0) else 0

    /** 只有成功或部分成功且已有成功文件的终态批次才需要执行本地存在性语义。 */
    private val shouldCheckLocalImages: Boolean
        get() = successCount > 0 &&
                (status == ImageExtractBatchData.STATUS_SUCCESS || status == ImageExtractBatchData.STATUS_PARTIAL_SUCCESS)
}

/**
 * 下载记录页一次性动作。
 *
 * 需要 Activity/Composable 参与的动作通过 SharedFlow 发出，例如系统删除授权和导航；普通状态仍保存在 UiState。
 */
sealed class DownloadHistoryAction {
    /** 跳转到视频下载页并观察新创建的重新下载任务。 */
    data class NavigateVideoDownload(val taskId: Long) : DownloadHistoryAction()

    /** 请求系统公共媒体删除授权，用户确认后页面需要把结果回传给 ViewModel。 */
    data class RequestMediaDeletePermission(val request: IntentSenderRequest) : DownloadHistoryAction()
}

/** 待系统授权后继续执行的删除上下文，确保用户取消授权时记录不会消失。 */
private data class PendingDelete(
    /** 删除发生在哪个 Tab，用于授权成功后删除对应表行。 */
    val tab: DownloadHistoryTab,

    /** 本次要删除的记录 id 集合。 */
    val ids: Set<Long>,

    /** 本次要删除的本地媒体引用；授权成功后只处理这些精确关联的文件。 */
    val mediaRefs: List<HistoryMediaRef>,
)

/** 下载记录关联的单个本地媒体引用；只能是 content URI 或旧系统文件路径之一。 */
private data class HistoryMediaRef(
    /** Android 10+ MediaStore URI；不为空时优先作为读取和删除身份。 */
    val uri: Uri? = null,

    /** Android 10 以下或临时缓存文件路径；为空时不做文件路径删除。 */
    val path: String? = null,
)

/** 单个媒体删除结果，用于决定是否保留记录和生成结果汇总。 */
private sealed class MediaDeleteResult {
    /** 已成功删除，或文件原本就不存在且可以继续删除记录。 */
    data object SuccessOrMissing : MediaDeleteResult()

    /** 删除失败但没有可恢复授权，调用方应保留记录或提示失败。 */
    data object Failed : MediaDeleteResult()

    /** Android 10 单个媒体需要系统授权；授权完成后应继续当前删除流程。 */
    data class NeedsPermission(val request: IntentSenderRequest) : MediaDeleteResult()
}

/**
 * 下载记录总数和运行中数量。
 *
 * 这些值来自数据库 COUNT 查询，不触发下载记录实体或媒体文件的全量读取；页面用它们控制标题栏按钮和清空提示。
 */
private data class DownloadHistoryCounts(
    /** 视频历史总数。 */
    val videoCount: Int,

    /** 图片历史总数。 */
    val imageCount: Int,

    /** 仍在下载或合并的视频任务数量。 */
    val videoRunningCount: Int,

    /** 仍在下载的图片批次数量。 */
    val imageRunningCount: Int,
)

/**
 * 下载记录页 ViewModel。
 *
 * 负责提供视频/图片分页历史流、读取分页项的本地媒体元信息、处理多选删除、清空当前分类和重新下载。
 * 分页流是冷流，只有页面在 STARTED 生命周期内收集当前 Tab 时才会触发数据库分页加载，避免 ViewModel 初始化时抢先扫描媒体。
 * 文件删除严格按数据库保存的 URI/路径执行，不做同名反查，避免误删公共目录里的其他媒体。
 */
@HiltViewModel
class DownloadHistoryVm @Inject constructor(
    /** 应用级 Context，用于读取 MediaStore、启动 WorkManager 任务和获取字符串资源。 */
    @param:ApplicationContext private val appContext: Context,

    /** 视频下载仓库，提供历史流、任务克隆和精确删除接口。 */
    private val downloadRepository: DownloadRepository,

    /** 图片提取仓库，提供批次历史、批次克隆和精确删除接口。 */
    private val imageExtractRepository: ImageExtractRepository,
) : ViewModel() {

    /** 当前选中的下载记录分类。 */
    private val selectedTab = MutableStateFlow(DownloadHistoryTab.VIDEO)

    /** 当前 Tab 是否处于多选管理态。 */
    private val selectionMode = MutableStateFlow(false)

    /** 当前 Tab 已选择的记录 id。 */
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 后台操作忙碌状态，防止删除或重新下载期间重复点击。 */
    private val busy = MutableStateFlow(false)

    /** 操作结果提示；页面展示后会清空。 */
    private val message = MutableStateFlow<String?>(null)

    /** 等待系统媒体删除授权的上下文；为空表示没有未完成授权流程。 */
    private var pendingDelete: PendingDelete? = null

    /** 下载记录页一次性动作流。 */
    private val _actions = MutableSharedFlow<DownloadHistoryAction>(extraBufferCapacity = 1)

    /** 页面订阅的一次性动作流，主要用于导航和系统授权。 */
    val actions = _actions.asSharedFlow()

    /**
     * 视频历史分页流。
     *
     * 与剪贴列表页一致，分页流本身保持稳定并缓存到 ViewModel 生命周期；页面层负责在 STARTED 生命周期和当前 Tab 可见时收集。
     * 这样切走 Tab 不会用空流覆盖 LazyPagingItems，切回时可以复用 Paging 缓存和列表位置。
     */
    val pagedVideos = Pager(
        config = PagingConfig(
            pageSize = HISTORY_PAGE_SIZE,
            prefetchDistance = 5,
            enablePlaceholders = false
        )
    ) {
        downloadRepository.pagingHistory()
    }.flow.map { pagingData: PagingData<DownloadTaskData> ->
        pagingData.map { task ->
            withContext(Dispatchers.IO) { task.toVideoHistoryItem() }
        }
    }.cachedIn(viewModelScope)

    /**
     * 图片历史分页流。
     *
     * 分页流对象保持稳定，页面切到图片 Tab 后再收集；切走时由 Compose 释放收集者，但 ViewModel 内的 cachedIn 缓存仍可服务下次切回。
     */
    val pagedImages = Pager(
        config = PagingConfig(
            pageSize = HISTORY_PAGE_SIZE,
            prefetchDistance = 5,
            enablePlaceholders = false
        )
    ) {
        imageExtractRepository.pagingHistory()
    }.flow.map { pagingData: PagingData<ImageExtractBatchData> ->
        pagingData.map { batch ->
            withContext(Dispatchers.IO) {
                val fileRefs = imageExtractRepository.getHistoryFileRefs(batch.id)
                batch.toImageHistoryBatch(fileRefs)
            }
        }
    }.cachedIn(viewModelScope)

    /** 历史总数和运行中数量；只做 COUNT 级查询，不加载完整记录。 */
    private val historyCounts = combine(
        downloadRepository.observeHistoryCount(),
        imageExtractRepository.observeHistoryCount(),
        downloadRepository.observeRunningHistoryCount(),
        imageExtractRepository.observeRunningHistoryCount()
    ) { videoCount, imageCount, videoRunningCount, imageRunningCount ->
        DownloadHistoryCounts(
            videoCount = videoCount,
            imageCount = imageCount,
            videoRunningCount = videoRunningCount,
            imageRunningCount = imageRunningCount
        )
    }

    /** 当前选中项里是否有运行中任务；按 id 做 COUNT 查询，避免分页列表未加载完整导致误判。 */
    private val selectedHasRunning = combine(selectedTab, selectedIds) { tab, ids -> tab to ids }
        .map { (tab, ids) ->
            if (ids.isEmpty()) {
                false
            } else {
                withContext(Dispatchers.IO) {
                    when (tab) {
                        DownloadHistoryTab.VIDEO -> downloadRepository.countRunningTasks(ids) > 0
                        DownloadHistoryTab.IMAGE -> imageExtractRepository.countRunningBatches(ids) > 0
                    }
                }
            }
        }

    /** 列表与选中态组合后的中间状态，避免使用过多 Flow 参数导致重载不清晰。 */
    private val contentState = combine(
        selectedTab,
        historyCounts,
        selectionMode,
        selectedIds,
        selectedHasRunning
    ) { tab, counts, inSelection, ids, hasRunning ->
        DownloadHistoryUiState(
            selectedTab = tab,
            videoCount = counts.videoCount,
            imageCount = counts.imageCount,
            videoRunningCount = counts.videoRunningCount,
            imageRunningCount = counts.imageRunningCount,
            selectionMode = inSelection,
            selectedIds = ids,
            selectedHasRunning = hasRunning
        )
    }

    /** 页面观察的统一状态。 */
    val uiState = combine(
        contentState,
        busy,
        message
    ) { content, isBusy, msg ->
        content.copy(
            busy = isBusy,
            message = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadHistoryUiState()
    )

    /** 切换分类 Tab，并清空多选状态，避免跨分类复用 id 导致误删。 */
    fun selectTab(tab: DownloadHistoryTab) {
        if (selectedTab.value == tab) return
        exitSelection()
        selectedTab.value = tab
    }

    /** 进入多选管理态；如果传入记录 id，会同时选中该记录，适配长按进入管理。 */
    fun enterSelection(id: Long? = null) {
        selectionMode.value = true
        if (id != null) {
            selectedIds.value = selectedIds.value + id
        }
    }

    /** 退出多选态并清空当前选择。 */
    fun exitSelection() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    /** 切换单条记录选中状态；取消到空集合时保留多选态，方便用户继续选择。 */
    fun toggleSelected(id: Long) {
        val cur = selectedIds.value
        selectedIds.value = if (id in cur) cur - id else cur + id
        selectionMode.value = true
    }

    /** 全选当前 Tab 中的全部记录；没有记录时保持空选择。 */
    fun selectAllCurrentTab() {
        val tab = selectedTab.value
        viewModelScope.launch(Dispatchers.IO) {
            val ids = when (tab) {
                DownloadHistoryTab.VIDEO -> downloadRepository.getHistoryIds()
                DownloadHistoryTab.IMAGE -> imageExtractRepository.getHistoryIds()
            }.toSet()
            if (selectedTab.value != tab) return@launch
            selectedIds.value = ids
            selectionMode.value = ids.isNotEmpty()
        }
    }

    /** 清空当前提示文案，避免 Toast/Snackbar 因状态恢复重复展示。 */
    fun clearMessage() {
        message.value = null
    }

    /** 重新下载视频：创建全新任务、入队 Worker，并通知页面跳转到下载页观察新任务。 */
    fun retryVideo(taskId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            val newTaskId = downloadRepository.createRetryTask(taskId)
            if (newTaskId == null) {
                message.value = appContext.getString(R.string.base_general_the_download_task_was_not_found)
            } else {
                DownloadVideoWorker.enqueue(appContext, newTaskId)
                _actions.emit(DownloadHistoryAction.NavigateVideoDownload(newTaskId))
            }
            busy.value = false
        }
    }

    /** 重新下载图片批次：克隆旧批次候选为新批次并入队图片下载 Worker，旧记录和旧公共文件保持不变。 */
    fun retryImageBatch(batchId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            val newBatchId = imageExtractRepository.cloneBatchForRetry(batchId)
            if (newBatchId == null) {
                message.value = appContext.getString(R.string.base_general_download_history_retry_failed)
            } else {
                DownloadImagesWorker.enqueue(appContext, newBatchId)
                message.value = appContext.getString(R.string.base_general_download_history_retry_started)
            }
            busy.value = false
        }
    }

    /** 删除当前选中的记录；deleteFiles 为 true 时会先删除记录精确关联的本地媒体。 */
    fun deleteSelected(deleteFiles: Boolean) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        deleteRecords(selectedTab.value, ids, deleteFiles)
    }

    /** 清空当前分类下所有记录；只作用于当前 Tab，不影响另一类下载历史或其他业务表。 */
    fun clearCurrentTab(deleteFiles: Boolean) {
        val tab = selectedTab.value
        viewModelScope.launch(Dispatchers.IO) {
            val ids = when (tab) {
                DownloadHistoryTab.VIDEO -> downloadRepository.getHistoryIds()
                DownloadHistoryTab.IMAGE -> imageExtractRepository.getHistoryIds()
            }.toSet()
            if (ids.isEmpty() || selectedTab.value != tab) return@launch
            deleteRecords(tab, ids, deleteFiles)
        }
    }

    /** 系统媒体删除授权返回后继续处理；用户取消时保留记录，避免记录消失但文件仍在。 */
    fun onMediaDeletePermissionResult(granted: Boolean) {
        val pending = pendingDelete ?: return
        if (!granted) {
            pendingDelete = null
            message.value = appContext.getString(R.string.base_general_download_history_delete_cancelled_keep_records)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            val result = deleteMediaRefsDirectly(pending.mediaRefs)
            if (result.needsPermission != null) {
                // Android 10 只能对单个媒体项走 RecoverableSecurityException 授权；若授权后仍有其它媒体项要求授权，
                // 为避免连续弹出多个系统确认框，保留记录让用户稍后重试或改选“仅删除记录”。
                pendingDelete = null
                message.value = appContext.getString(R.string.base_general_download_history_more_permission_required_keep_records)
                busy.value = false
                return@launch
            }

            finishDeleteRecords(pending.tab, pending.ids, result.failedCount)
            pendingDelete = null
            busy.value = false
        }
    }

    /**
     * 执行记录删除。
     *
     * 进行中任务会先取消 Worker；如果需要删除本地文件，Android 11+ 先发起合并系统授权，授权成功后再删数据库记录。
     */
    private fun deleteRecords(tab: DownloadHistoryTab, ids: Set<Long>, deleteFiles: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            val mediaRefs = collectMediaRefsAndCancelRunning(tab, ids)
            if (!deleteFiles) {
                finishDeleteRecords(tab, ids, failedFileCount = 0, deletedFiles = false)
                busy.value = false
                return@launch
            }

            val contentUris = mediaRefs.mapNotNull { it.uri }.distinct()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && contentUris.isNotEmpty()) {
                val request = MediaStore.createDeleteRequest(appContext.contentResolver, contentUris)
                pendingDelete = PendingDelete(tab, ids, mediaRefs)
                _actions.emit(DownloadHistoryAction.RequestMediaDeletePermission(IntentSenderRequest.Builder(request.intentSender).build()))
                busy.value = false
                return@launch
            }

            val result = deleteMediaRefsDirectly(mediaRefs)
            if (result.needsPermission != null) {
                pendingDelete = PendingDelete(tab, ids, mediaRefs)
                _actions.emit(DownloadHistoryAction.RequestMediaDeletePermission(result.needsPermission))
                busy.value = false
                return@launch
            }

            finishDeleteRecords(tab, ids, result.failedCount)
            busy.value = false
        }
    }

    /** 收集记录精确关联的媒体 URI/路径，并取消正在进行的 Worker，保证后续删除不会被迟到回写覆盖。 */
    private suspend fun collectMediaRefsAndCancelRunning(tab: DownloadHistoryTab, ids: Set<Long>): List<HistoryMediaRef> {
        return when (tab) {
            DownloadHistoryTab.VIDEO -> {
                downloadRepository.getTasks(ids).flatMap { task ->
                    if (task.status == DownloadTaskData.STATUS_DOWNLOADING || task.status == DownloadTaskData.STATUS_MERGING) {
                        DownloadVideoWorker.cancel(appContext, task.id)
                    }
                    buildList {
                        add(task.savePath.toMediaRef())
                        add(task.pendingOutputUri.toMediaRef())
                    }.filterNotNull()
                }
            }

            DownloadHistoryTab.IMAGE -> {
                imageExtractRepository.getBatchesWithItems(ids).flatMap { (batch, items) ->
                    if (batch.status == ImageExtractBatchData.STATUS_DOWNLOADING) {
                        DownloadImagesWorker.cancel(appContext, batch.id)
                    }
                    collectImageMediaRefs(batch, items)
                }
            }
        }.distinct()
    }

    /** 收集图片批次关联的精确媒体引用；旧系统成功图片需要用 outputDir + finalName 定位最终公开文件。 */
    private fun collectImageMediaRefs(
        batch: ImageExtractBatchData,
        items: List<ImageExtractItemData>
    ): List<HistoryMediaRef> {
        val itemFinalNames = items.mapNotNull { it.finalName?.takeIf(String::isNotBlank) }.toSet()
        val refs = buildList {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                addAll(collectLegacyImageDeleteRefs(batch.outputDir, itemFinalNames))
            }
            items.forEach { item ->
                add(item.outputUri.toMediaRef())
                add(item.tempPath.toMediaRef())
            }
        }.filterNotNull()
        return refs
    }

    /**
     * 旧系统删除图片时优先利用批次目录。
     *
     * 如果目录内全部文件都属于当前批次，可以直接删除整个目录；如果用户后来放入了额外文件或子目录，
     * 只删除记录匹配的最终图片，避免误删不属于本批次的数据。
     */
    private fun collectLegacyImageDeleteRefs(outputDir: String?, finalNames: Set<String>): List<HistoryMediaRef> {
        if (finalNames.isEmpty()) return emptyList()
        val folder = resolveLegacyImageFolder(outputDir) ?: return emptyList()
        val children = runCatching { folder.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        if (children.isEmpty()) return emptyList()

        val childFileNames = children.filter { it.isFile }.map { it.name }.toSet()
        val hasUntrackedFiles = childFileNames.any { it !in finalNames }
        val hasSubDirectories = children.any { it.isDirectory }
        return if (!hasUntrackedFiles && !hasSubDirectories && childFileNames.isNotEmpty()) {
            listOf(HistoryMediaRef(path = folder.absolutePath))
        } else {
            finalNames
                .filter { it in childFileNames }
                .map { HistoryMediaRef(path = File(folder, it).absolutePath) }
        }
    }

    /** 删除数据库记录并更新选择态和结果提示；本地文件删除失败时保留对应提示，但仍删除记录。 */
    private suspend fun finishDeleteRecords(
        tab: DownloadHistoryTab,
        ids: Set<Long>,
        failedFileCount: Int,
        deletedFiles: Boolean = true,
    ) {
        when (tab) {
            DownloadHistoryTab.VIDEO -> downloadRepository.deleteTasks(ids)
            DownloadHistoryTab.IMAGE -> imageExtractRepository.deleteBatches(ids)
        }
        exitSelection()
        message.value = when {
            !deletedFiles -> appContext.getString(R.string.base_general_download_history_delete_record_summary, ids.size)
            failedFileCount > 0 -> appContext.getString(R.string.base_general_download_history_delete_with_file_failed_summary, ids.size, failedFileCount)
            else -> appContext.getString(R.string.base_general_download_history_delete_with_file_summary, ids.size)
        }
    }

    /** 直接删除一组媒体引用；遇到 Android 10 可恢复授权时返回授权请求并暂停数据库删除。 */
    private fun deleteMediaRefsDirectly(mediaRefs: List<HistoryMediaRef>): BatchDeleteResult {
        var failedCount = 0
        mediaRefs.distinct().forEach { ref ->
            when (val result = deleteSingleMediaRef(ref)) {
                MediaDeleteResult.SuccessOrMissing -> Unit
                MediaDeleteResult.Failed -> failedCount += 1
                is MediaDeleteResult.NeedsPermission -> return BatchDeleteResult(failedCount, result.request)
            }
        }
        return BatchDeleteResult(failedCount, null)
    }

    /** 删除单个媒体引用；content URI 使用 ContentResolver，旧系统路径支持文件和安全确认后的批次目录。 */
    private fun deleteSingleMediaRef(ref: HistoryMediaRef): MediaDeleteResult {
        ref.uri?.let { uri ->
            if (!uri.existsAsContentUri()) return MediaDeleteResult.SuccessOrMissing
            return runCatching {
                val count = appContext.contentResolver.delete(uri, null, null)
                if (count >= 0) MediaDeleteResult.SuccessOrMissing else MediaDeleteResult.Failed
            }.getOrElse { tr ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && tr is RecoverableSecurityException) {
                    MediaDeleteResult.NeedsPermission(IntentSenderRequest.Builder(tr.userAction.actionIntent.intentSender).build())
                } else {
                    logE(TAG, tr) { "deleteSingleMediaRef: 删除 content URI 失败 uri=$uri" }
                    MediaDeleteResult.Failed
                }
            }
        }

        val path = ref.path?.takeIf { it.isNotBlank() } ?: return MediaDeleteResult.SuccessOrMissing
        val file = File(path)
        if (!file.exists()) return MediaDeleteResult.SuccessOrMissing
        val deleted = runCatching {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }.getOrDefault(false)
        return if (deleted) {
            MediaDeleteResult.SuccessOrMissing
        } else {
            logD(TAG) { "deleteSingleMediaRef: 删除文件失败 path=$path" }
            MediaDeleteResult.Failed
        }
    }

    /** 将 Room 视频任务映射为历史页模型，并同步读取本地媒体元信息。 */
    private fun DownloadTaskData.toVideoHistoryItem(): DownloadHistoryVideoItem {
        val ref = savePath.toMediaRef() ?: pendingOutputUri.toMediaRef()
        val localExists = ref?.exists() ?: false
        val readableRef = ref?.takeIf { localExists }
        val metadata = readableRef?.let(::readVideoMetadata) ?: VideoMetadata()
        val thumbnail = readableRef?.let(::readVideoThumbnail)
        return DownloadHistoryVideoItem(
            id = id,
            title = fileName,
            status = status,
            progress = progress.coerceIn(0, 100),
            errorMsg = errorMsg,
            updateTime = updateTime,
            localPath = savePath ?: pendingOutputUri,
            localExists = localExists,
            sizeBytes = metadata.sizeBytes,
            durationMs = metadata.durationMs,
            thumbnail = thumbnail
        )
    }

    /** 将图片批次和轻量文件引用映射为历史页模型，通过文件夹短路和批量查询避免逐张判断本地文件是否被删除。 */
    private fun ImageExtractBatchData.toImageHistoryBatch(fileRefs: List<ImageHistoryFileRef>): DownloadHistoryImageBatch {
        val imageRefs = if (shouldCheckLocalImages()) {
            readableImageRefs(outputDir, fileRefs)
        } else {
            // 下载中批次可能已经写入 outputDir 但尚未发布图片；此时跳过本地校验，避免误判和无意义查询。
            emptyList()
        }
        return DownloadHistoryImageBatch(
            id = id,
            title = pageName,
            status = status,
            totalCount = totalCount,
            successCount = successCount,
            failedCount = failedCount,
            filteredCount = filteredCount,
            outputDir = outputDir,
            updateTime = updateTime,
            imageUris = imageRefs
        )
    }

    /** 只有已产生成功文件的终态批次才需要检查本地图片是否仍可读取。 */
    private fun ImageExtractBatchData.shouldCheckLocalImages(): Boolean {
        return successCount > 0 &&
                (status == ImageExtractBatchData.STATUS_SUCCESS || status == ImageExtractBatchData.STATUS_PARTIAL_SUCCESS)
    }

    /** 按系统版本批量判断图片批次中哪些文件仍可读取，避免几百张图片逐个查询拖慢列表刷新。 */
    private fun readableImageRefs(outputDir: String?, fileRefs: List<ImageHistoryFileRef>): List<String> {
        if (fileRefs.isEmpty()) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            readableMediaStoreImageUris(outputDir, fileRefs)
        } else {
            readableLegacyImagePaths(outputDir, fileRefs)
        }
    }

    /** Android 10+ 先按 RELATIVE_PATH 判断目录是否还有未回收站媒体项，再用 MediaStore id 分块批量校验。 */
    private fun readableMediaStoreImageUris(outputDir: String?, fileRefs: List<ImageHistoryFileRef>): List<String> {
        val relativePath = normalizeImageOutputDir(outputDir)
        if (relativePath != null && !mediaStoreImageFolderHasVisibleItems(relativePath)) {
            return emptyList()
        }

        val standardRefs = mutableListOf<Pair<ImageHistoryFileRef, Long>>()
        val fallbackRefs = mutableListOf<ImageHistoryFileRef>()
        fileRefs.forEach { ref ->
            val uri = ref.outputUri?.toUriOrNull()
            val mediaId = uri
                ?.takeIf { it.isStandardMediaStoreImageUri() }
                ?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
            if (mediaId != null && mediaId >= 0) {
                standardRefs += ref to mediaId
            } else {
                fallbackRefs += ref
            }
        }

        val visibleIds = queryVisibleImageIds(standardRefs.map { it.second })
        val visibleStandardUris = standardRefs
            .filter { (_, mediaId) -> mediaId in visibleIds }
            .mapNotNull { (ref, _) -> ref.outputUri }
            .toSet()
        val readableFallbackUris = fallbackRefs
            .mapNotNull { ref -> ref.outputUri?.takeIf { it.toMediaRef()?.exists() == true } }
            .toSet()
        val readableUris = visibleStandardUris + readableFallbackUris
        return fileRefs.mapNotNull { ref -> ref.outputUri?.takeIf { it in readableUris } }
    }

    /** 查询指定 MediaStore 图片目录下是否还有未被 Android 11+ 回收站隐藏的媒体项。 */
    private fun mediaStoreImageFolderHasVisibleItems(relativePath: String): Boolean {
        val selectionParts = mutableListOf("${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            selectionParts += "${MediaStore.MediaColumns.IS_TRASHED} != 1"
        }
        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                selectionParts.joinToString(" AND "),
                arrayOf(relativePath, "$relativePath/"),
                null
            )?.use { cursor -> cursor.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /** 分块查询仍在 MediaStore 中可见的图片 id；Android 11+ 已进回收站的图片按不可读处理。 */
    private fun queryVisibleImageIds(mediaIds: List<Long>): Set<Long> {
        if (mediaIds.isEmpty()) return emptySet()
        val result = mutableSetOf<Long>()
        mediaIds.distinct().chunked(MEDIASTORE_QUERY_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selectionParts = mutableListOf("${MediaStore.Images.Media._ID} IN ($placeholders)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                selectionParts += "${MediaStore.MediaColumns.IS_TRASHED} != 1"
            }
            val selectionArgs = chunk.map(Long::toString).toTypedArray()
            runCatching {
                appContext.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    selectionParts.joinToString(" AND "),
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    while (idIndex >= 0 && cursor.moveToNext()) {
                        result += cursor.getLong(idIndex)
                    }
                }
            }.onFailure { tr ->
                logE(TAG, tr) { "queryVisibleImageIds: 批量查询图片可读状态失败 count=${chunk.size}" }
            }
        }
        return result
    }

    /** Android 9 及以下通过一次读取批次目录文件名集合来判断成功图片是否仍存在。 */
    private fun readableLegacyImagePaths(outputDir: String?, fileRefs: List<ImageHistoryFileRef>): List<String> {
        val folder = resolveLegacyImageFolder(outputDir) ?: return emptyList()
        val existingNames = runCatching {
            folder.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
        if (existingNames.isEmpty()) return emptyList()

        return fileRefs.mapNotNull { ref ->
            val finalName = ref.finalName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            File(folder, finalName).absolutePath.takeIf { finalName in existingNames }
        }
    }

    /** 旧系统真实保存目录是 Pictures/clipMaster/<folderName>，必要时兼容检查 outputDir 形态对应的 DCIM 目录。 */
    private fun resolveLegacyImageFolder(outputDir: String?): File? {
        val folderName = outputDir.extractImageFolderName() ?: return null
        val picturesFolder = File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), LEGACY_IMAGE_PARENT_DIR),
            folderName
        )
        if (picturesFolder.isDirectory) return picturesFolder

        val dcimFolder = File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), LEGACY_IMAGE_PARENT_DIR),
            folderName
        )
        return dcimFolder.takeIf { it.isDirectory }
    }

    /** 从批次 outputDir 中提取最后一级目录名；outputDir 是展示/相册定位值，不直接当作旧系统真实路径。 */
    private fun String?.extractImageFolderName(): String? {
        return normalizeImageOutputDir(this)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    /** 安全解析 URI 字符串；解析失败返回 null，让调用方走兜底路径。 */
    private fun String.toUriOrNull(): Uri? {
        return runCatching { toUri() }.getOrNull()
    }

    /** 判断是否为当前保存链路产生的标准 MediaStore 图片 URI，只有这类 URI 才能可靠解析 id 后批量查询。 */
    private fun Uri.isStandardMediaStoreImageUri(): Boolean {
        return scheme == URI_SCHEME_CONTENT &&
                authority == MediaStore.AUTHORITY &&
                pathSegments.contains("images") &&
                pathSegments.contains("media")
    }

    /** 将字符串路径转换为媒体引用；content:// 作为 URI，其余字符串作为旧系统文件路径处理。 */
    private fun String?.toMediaRef(): HistoryMediaRef? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { raw.toUri() }.getOrNull()
        return if (uri?.scheme == URI_SCHEME_CONTENT) {
            HistoryMediaRef(uri = uri)
        } else {
            HistoryMediaRef(path = raw)
        }
    }

    /** 判断媒体引用当前是否仍可读取；失败表示记录页应展示已删除或占位状态。 */
    private fun HistoryMediaRef.exists(): Boolean {
        uri?.let { return it.existsAsContentUri() }
        val path = path ?: return false
        return File(path).exists()
    }

    /** 判断 content URI 是否仍存在；Android 11+ 相册删除可能先进入回收站，查到已回收时也按不可读处理。 */
    private fun Uri.existsAsContentUri(): Boolean {
        val queried = runCatching {
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.MediaColumns.IS_TRASHED)
                }
            }.toTypedArray()
            appContext.contentResolver.query(this, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val trashedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    if (trashedIndex >= 0 && cursor.getInt(trashedIndex) != 0) return@use false
                }
                true
            }
        }.getOrNull()
        if (queried != null) return queried

        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(this, "r")?.use { true } == true
        }.getOrDefault(false)
    }

    /** 读取视频大小和时长；content URI 优先通过 MediaStore 查询，时长缺失时交给 MediaMetadataRetriever 兜底。 */
    private fun readVideoMetadata(ref: HistoryMediaRef): VideoMetadata {
        val size = ref.uri?.let(::queryContentSize) ?: ref.path?.let { File(it).takeIf(File::exists)?.length() }
        val durationFromQuery = ref.uri?.let(::queryVideoDuration)
        val duration = durationFromQuery ?: readVideoDurationWithRetriever(ref)
        return VideoMetadata(sizeBytes = size?.takeIf { it > 0L }, durationMs = duration?.takeIf { it > 0L })
    }

    /** 从 MediaStore 查询媒体大小，失败时返回空值，让 UI 展示未知。 */
    private fun queryContentSize(uri: Uri): Long? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLongOrNull(MediaStore.MediaColumns.SIZE) else null
            }
        }.getOrNull()
    }

    /** 从 MediaStore 查询视频时长，部分第三方 URI 不支持该列时自动返回空。 */
    private fun queryVideoDuration(uri: Uri): Long? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DURATION), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLongOrNull(MediaStore.Video.Media.DURATION) else null
            }
        }.getOrNull()
    }

    /** 使用 MediaMetadataRetriever 读取视频时长，兼容旧系统路径和 MediaStore 时长列缺失的情况。 */
    private fun readVideoDurationWithRetriever(ref: HistoryMediaRef): Long? {
        return runCatching {
            MediaMetadataRetriever().useCompat { retriever ->
                retriever.setSource(ref)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()
    }

    /** 读取本地视频首帧并缩放到列表可用尺寸；失败时返回空，由页面展示视频占位图。 */
    private fun readVideoThumbnail(ref: HistoryMediaRef): Bitmap? {
        return runCatching {
            MediaMetadataRetriever().useCompat { retriever ->
                retriever.setSource(ref)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.scaleDown(VIDEO_THUMB_MAX_EDGE)
            }
        }.getOrNull()
    }

    /** 为 MediaMetadataRetriever 设置数据源；content URI 与旧系统路径分别处理。 */
    private fun MediaMetadataRetriever.setSource(ref: HistoryMediaRef) {
        ref.uri?.let {
            setDataSource(appContext, it)
            return
        }
        setDataSource(ref.path ?: error("Video path is null"))
    }

    /** 兼容不同 API 的 MediaMetadataRetriever 释放方式，保证异常时也不泄漏底层解码资源。 */
    private inline fun <T> MediaMetadataRetriever.useCompat(block: (MediaMetadataRetriever) -> T): T {
        return try {
            block(this)
        } finally {
            release()
        }
    }

    /** 将过大的首帧按最大边长等比缩小，降低 Compose 列表持有 Bitmap 的内存压力。 */
    private fun Bitmap.scaleDown(maxEdge: Int): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= maxEdge || edge <= 0) return this
        val scale = maxEdge.toFloat() / edge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        if (scaled !== this) recycle()
        return scaled
    }

    /** 安全读取 Cursor Long 列，列不存在、为空或读取失败时返回 null。 */
    private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return runCatching { getLong(index) }.getOrNull()
    }
}

/** 批量媒体删除结果，needsPermission 不为空时调用方必须先请求系统授权再删除数据库记录。 */
private data class BatchDeleteResult(
    /** 删除失败的本地文件数量；已不存在的文件不计为失败。 */
    val failedCount: Int,

    /** Android 10 可恢复删除授权请求；为空表示无需额外授权。 */
    val needsPermission: IntentSenderRequest?,
)

/** 视频元信息读取结果，字段为空表示本地媒体不可读或系统未提供该信息。 */
private data class VideoMetadata(
    /** 视频文件大小，单位字节。 */
    val sizeBytes: Long? = null,

    /** 视频时长，单位毫秒。 */
    val durationMs: Long? = null,
)
