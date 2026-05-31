package com.cla.clip.master.ui.page.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.repository.SearchHistoryRepository
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * 搜索页时间筛选范围。
 *
 * 枚举只描述 UI 可选项，不直接保存时间戳；具体时间窗口由 ViewModel 在查询时按当前时间计算，
 * 这样“今天”和“近 7 天”不会因为页面长时间停留而持久化成过期的固定值。
 */
enum class SearchTimeFilter {
    /** 不限制剪贴记录时间。 */
    ALL,

    /** 只查看当天 00:00 到次日 00:00 之间的记录。 */
    TODAY,

    /** 查看最近 7 天的记录，按毫秒时间戳从当前时刻回推。 */
    LAST_7_DAYS,

    /** 查看最近 30 天的记录，按毫秒时间戳从当前时刻回推。 */
    LAST_30_DAYS
}

/**
 * 搜索筛选状态。
 *
 * 这个数据类是搜索页和 ViewModel 之间的稳定契约；所有字段都只保存用户选择，
 * 不保存派生出的 Paging 数据，避免筛选条件和结果流互相污染。
 */
data class SearchFilterState(
    /** 搜索框中的原始输入，Repository 会负责进一步清洗并转换为数据库查询。 */
    val query: String = "",

    /** 当前选择的时间范围。 */
    val timeFilter: SearchTimeFilter = SearchTimeFilter.ALL,

    /**
     * 当前选择的来源 App 包名集合。
     *
     * 空集合表示不过滤来源；使用 Set 是为了天然去重，避免用户反复点选同一个 App 后触发重复 SQL 参数。
     */
    val sourceAppPackages: Set<String> = emptySet(),
)

/**
 * 搜索页 ViewModel。
 *
 * 负责把关键词、时间范围和来源 App 多选条件组合成 Paging 查询，同时复用剪贴数据处理器提供的复制、删除和置顶能力。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clipRepository: Lazy<ClipRepository>,
    private val searchHistoryRepository: Lazy<SearchHistoryRepository>,
    private val clipboardDataProcessor: DefaultClipboardDataProcessor,
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {

    companion object {
        /** 搜索历史提示防抖时间，单位毫秒；只影响历史表查询，不延迟剪贴内容搜索结果。 */
        private const val HISTORY_QUERY_DEBOUNCE_MS = 180L
    }

    private val _filterState = MutableStateFlow(SearchFilterState())

    /**
     * 当前搜索页的数据范围。
     *
     * 同一个 SearchPage 会被普通搜索和折叠搜索复用；范围由导航路由在页面创建后写入，
     * 查询流会在路由写入范围后才创建 Pager，并随范围变化重新创建，保证折叠搜索首帧也不会读取普通数据。
     */
    private val visibilityScope = MutableStateFlow<ClipVisibilityScope?>(null)

    /**
     * 搜索页当前筛选条件。
     *
     * 暴露只读 StateFlow 是为了让 UI 能响应用户输入，同时防止页面层直接改内部状态造成查询流不同步。
     */
    val filterState: StateFlow<SearchFilterState> = _filterState

    /**
     * 来源 App 筛选候选项。
     *
     * 使用 stateIn 缓存最近一次列表，避免筛选弹窗每次展开都重新收集数据库 Flow。
     */
    val sourceApps: StateFlow<List<SourceAppData>> = clipRepository.get()
        .loadAllSourceApps()
        .stateIn(
            CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /**
     * 分页搜索结果。
     *
     * 每次筛选条件变化都会创建新的 Pager，Paging 会丢弃旧查询并加载新条件下的数据。
     */
    val pagedClips = combine(_filterState, visibilityScope.filterNotNull()) { state, scope -> state to scope }.flatMapLatest { (state, scope) ->
        val timeRange = state.timeFilter.toTimeRange()
        Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 5,
                enablePlaceholders = false
            )
        ) {
            clipRepository.get().searchClips(
                userInput = state.query,
                startTime = timeRange.startTime,
                endTime = timeRange.endTime,
                sourceAppPackages = state.sourceAppPackages,
                visibilityScope = scope
            )
        }.flow.map { pagingData ->
            pagingData.map { clipDetail -> clipDetail.toUi() }
        }
    }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )

    /**
     * 当前范围的搜索历史。
     *
     * 历史提示和内容搜索刻意分成两条流：内容搜索继续即时响应输入，历史查询轻量防抖，避免每次键入都刷新历史面板。
     */
    val searchHistories: StateFlow<List<SearchHistoryData>> = combine(
        _filterState
            .map { it.query }
            .debounce(HISTORY_QUERY_DEBOUNCE_MS),
        visibilityScope.filterNotNull()
    ) { query, scope -> query to scope }.flatMapLatest { (query, scope) ->
        searchHistoryRepository.get().observeHistories(scope = scope, keyword = query)
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    /** 更新搜索关键词，输入变化后会自动触发新的分页查询。 */
    fun updateQuery(query: String) {
        val singleLineQuery = query.toSingleLineSearchQuery()
        _filterState.update { it.copy(query = singleLineQuery) }
    }

    /**
     * 更新当前搜索范围。
     *
     * 页面只在路由范围变化时调用；如果范围未变，StateFlow 不会触发无意义的分页重建。
     */
    fun updateVisibilityScope(scope: ClipVisibilityScope) {
        visibilityScope.update { scope }
    }

    /** 更新时间筛选条件。 */
    fun updateTimeFilter(filter: SearchTimeFilter) {
        _filterState.update { it.copy(timeFilter = filter) }
    }

    /**
     * 批量更新来源 App 多选条件。
     *
     * 弹窗内部会先维护草稿选择，用户点击确认后才调用这里提交；提交时统一去重和排序，
     * 让相同选择集合不会因为点击顺序不同而造成无意义的筛选状态变化。
     *
     * 注意空字符串包名是合法筛选值，用来匹配历史数据里无法识别来源包名的“未知”来源；
     * 只有真正的空集合才表示“全部来源”，因此这里不能再把空字符串过滤掉。
     */
    fun updateSourceApps(packageNames: Set<String>) {
        val normalizedPackageNames = packageNames
            .map { it.trim() }
            .toSortedSet()
        _filterState.update { it.copy(sourceAppPackages = normalizedPackageNames) }
    }

    /**
     * 提交当前搜索词并写入搜索历史。
     *
     * 保存只在明确提交时发生，输入过程不会污染历史；当前范围尚未初始化时忽略保存，避免误写到普通范围。
     */
    fun submitCurrentQuery() {
        /** 当前搜索范围；范围尚未初始化时不能把历史误写到普通或折叠的错误分组。 */
        val scope = visibilityScope.value ?: return
        /** 当前搜索框内容；空白搜索只用于展示全部匹配结果，不写历史也不触发备份 dirty。 */
        val query = _filterState.value.query
        if (query.trim().isBlank()) return
        viewModelScope.launch {
            searchHistoryRepository.get().saveHistory(scope, query)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /**
     * 选择历史项。
     *
     * 点击历史只恢复关键词并刷新历史时间；时间筛选和来源 App 筛选继续沿用当前状态，符合“历史只代表关键词”的契约。
     */
    fun selectHistory(query: String) {
        val scope = visibilityScope.value ?: return
        val singleLineQuery = query.toSingleLineSearchQuery()
        _filterState.update { it.copy(query = singleLineQuery) }
        viewModelScope.launch {
            searchHistoryRepository.get().saveHistory(scope, singleLineQuery)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /**
     * 删除单条搜索历史。
     *
     * 删除历史不改搜索框、不重置筛选，也不主动刷新为其他关键词，只让历史提示列表自然更新。
     */
    fun deleteHistory(id: Long) {
        viewModelScope.launch {
            searchHistoryRepository.get().deleteHistory(id)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }

    /**
     * 清空当前搜索范围历史。
     *
     * 普通搜索和折叠搜索由 Repository 按 `ClipVisibilityScope` 隔离，清空一侧不会影响另一侧历史。
     */
    fun clearCurrentScopeHistory() {
        val scope = visibilityScope.value ?: return
        viewModelScope.launch {
            searchHistoryRepository.get().clearHistories(scope)
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
        }
    }
}

/**
 * 将搜索框输入规整成单行关键词。
 *
 * 用户可能从外部粘贴多行文本；这里仅把换行折叠为空格，保留普通空格和其它符号，避免改变既有关键词搜索语义。
 */
private fun String.toSingleLineSearchQuery(): String {
    return lineSequence()
        .joinToString(separator = " ")
}

/** 数据库搜索用的左闭右开时间范围。 */
private data class TimeRange(
    /** 起始时间戳，单位毫秒；为 null 表示不限开始。 */
    val startTime: Long?,

    /** 结束时间戳，单位毫秒；为 null 表示不限结束。 */
    val endTime: Long?,
)

/**
 * 将 UI 时间筛选转换成数据库查询区间。
 *
 * 今天使用自然日边界，最近 N 天使用当前时刻回推，二者语义不同，分别满足“当天”和“近期”两类常见搜索心智。
 */
private fun SearchTimeFilter.toTimeRange(): TimeRange {
    val now = System.currentTimeMillis()
    return when (this) {
        SearchTimeFilter.ALL -> TimeRange(startTime = null, endTime = null)
        SearchTimeFilter.TODAY -> {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = now
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val start = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            TimeRange(startTime = start, endTime = calendar.timeInMillis)
        }
        SearchTimeFilter.LAST_7_DAYS -> TimeRange(startTime = now - 7L * 24L * 60L * 60L * 1_000L, endTime = null)
        SearchTimeFilter.LAST_30_DAYS -> TimeRange(startTime = now - 30L * 24L * 60L * 60L * 1_000L, endTime = null)
    }
}
