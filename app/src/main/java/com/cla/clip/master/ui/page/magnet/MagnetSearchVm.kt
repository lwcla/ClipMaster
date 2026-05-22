package com.cla.clip.master.ui.page.magnet

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.MagnetSearchHistoryData
import com.cla.clip.base.general.magnet.MagnetTextNormalizer
import com.cla.clip.base.general.magnet.cache.MagnetSearchResult
import com.cla.clip.base.general.magnet.cache.MagnetSourceCacheState
import com.cla.clip.base.general.magnet.cache.MagnetSourceSearchRepository
import com.cla.clip.base.general.magnet.cache.MagnetSourceSyncPhase
import com.cla.clip.base.general.magnet.cache.MagnetSourceSyncProgress
import com.cla.clip.base.general.repository.MagnetRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MagnetSearchVm"

/**
 * 磁力搜索页 ViewModel。
 *
 * 页面可见时才收集分页结果；ViewModel 只维护用户输入、缓存状态和搜索历史，Academic Torrents 索引同步交给缓存仓库。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MagnetSearchVm @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val sourceRepository: MagnetSourceSearchRepository,
    private val magnetRepository: MagnetRepository,
    private val magnetActionHandler: MagnetActionHandler,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val cacheState = MutableStateFlow(MagnetSourceCacheState())
    private val message = MutableStateFlow<String?>(null)
    private val searchVersion = MutableStateFlow(0)
    private var initialQueryApplied = false

    /** 磁力搜索输入与缓存状态。 */
    val uiState: StateFlow<MagnetSearchUiState> = kotlinx.coroutines.flow.combine(
        query,
        cacheState,
        message
    ) { query, cache, message ->
        MagnetSearchUiState(
            query = query,
            cacheState = cache,
            message = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MagnetSearchUiState()
    )

    /** 同步进度只包含阶段和数量，不包含搜索词、标题或 hash。 */
    val syncProgress: StateFlow<MagnetSourceSyncProgress> = sourceRepository.progress

    /** 聚焦搜索框时展示的磁力搜索历史，轻量防抖避免每次键入都查主库。 */
    val histories: StateFlow<List<MagnetSearchHistoryData>> = query
        .debounce(HISTORY_QUERY_DEBOUNCE_MS)
        .flatMapLatest { keyword -> magnetRepository.observeSearchHistories(keyword) }
        .stateIn(
            CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /** 当前搜索结果分页；输入变化 300ms 后重建 Pager，过短关键词返回空分页。 */
    val pagedResults = kotlinx.coroutines.flow.combine(
        query
            .debounce(SEARCH_QUERY_DEBOUNCE_MS)
            .distinctUntilChanged(),
        searchVersion
    ) { keyword, version -> keyword to version }
        .flatMapLatest { (keyword, _) ->
            Pager(
                config = PagingConfig(
                    pageSize = SEARCH_PAGE_SIZE,
                    prefetchDistance = 5,
                    enablePlaceholders = false
                )
            ) {
                sourceRepository.search(keyword)
            }.flow
        }
        .cachedIn(viewModelScope)

    init {
        refreshCacheState()
    }

    /** 路由首帧关键词只应用一次，避免配置变化或重组覆盖用户正在编辑的输入。 */
    fun applyInitialQuery(initialQuery: String) {
        if (initialQueryApplied) return
        initialQueryApplied = true
        updateQuery(initialQuery)
    }

    /** 更新搜索关键词；输入规整为单行并限制长度，避免长剪贴内容直接进入搜索流。 */
    fun updateQuery(rawQuery: String) {
        query.update { MagnetTextNormalizer.normalizeDisplayQuery(rawQuery) }
    }

    /** 手动提交当前关键词并写入磁力搜索历史。 */
    fun submitCurrentQuery() {
        val currentQuery = query.value
        if (currentQuery.length < MagnetSourceSearchRepository.MIN_SEARCH_QUERY_LENGTH) {
            message.value = appContext.getString(R.string.base_general_magnet_query_too_short)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            magnetRepository.saveSearchHistory(currentQuery)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /** 选择历史会恢复关键词并刷新历史时间。 */
    fun selectHistory(history: MagnetSearchHistoryData) {
        updateQuery(history.query)
        viewModelScope.launch(Dispatchers.IO) {
            magnetRepository.saveSearchHistory(history.query)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /** 删除单条磁力搜索历史，不影响当前关键词。 */
    fun deleteHistory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            magnetRepository.deleteSearchHistory(id)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /** 清空全部磁力搜索历史；磁力记录不受影响。 */
    fun clearHistories() {
        viewModelScope.launch(Dispatchers.IO) {
            magnetRepository.clearSearchHistories()
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /** 同步 Academic Torrents 索引；force 为 true 时绕过短时间冷却。 */
    fun syncSource(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = sourceRepository.sync(force)
            cacheState.value = state
            searchVersion.update { it + 1 }
            if (syncProgress.value.phase == MagnetSourceSyncPhase.Completed) {
                logD(TAG) { "磁力源同步完成 reasonCode=${state.reason.code} itemCount=${state.itemCount}" }
            }
        }
    }

    /** 复制并打开外部下载器。 */
    fun copyAndOpen(result: MagnetSearchResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val actionResult = magnetActionHandler.copyAndOpenSearchResult(result, query.value)
            message.value = appContext.getString(actionResult.messageRes)
        }
    }

    /** 仅复制 magnet URI。 */
    fun copyOnly(result: MagnetSearchResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val actionResult = magnetActionHandler.copySearchResult(result, query.value)
            message.value = appContext.getString(actionResult.messageRes)
        }
    }

    /** 清空一次性提示。 */
    fun clearMessage() {
        message.value = null
    }

    private fun refreshCacheState() {
        viewModelScope.launch(Dispatchers.IO) {
            cacheState.value = sourceRepository.getCacheState()
        }
    }

    private companion object {
        private const val HISTORY_QUERY_DEBOUNCE_MS = 180L
        private const val SEARCH_QUERY_DEBOUNCE_MS = 300L
        private const val SEARCH_PAGE_SIZE = 20
    }
}

/** 磁力搜索页稳定 UI state；Paging 数据和历史流独立暴露，避免大对象进入状态组合。 */
data class MagnetSearchUiState(
    val query: String = "",
    val cacheState: MagnetSourceCacheState = MagnetSourceCacheState(),
    val message: String? = null,
)
