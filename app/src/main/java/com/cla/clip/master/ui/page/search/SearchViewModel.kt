package com.cla.clip.master.ui.page.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import com.cla.clip.master.paging.clipPagingConfig
import androidx.paging.PagingData
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.search.ClipSearchField
import kotlinx.coroutines.flow.Flow
import androidx.paging.map
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.master.sourceapp.ClipSourceNameAssembler
import com.cla.clip.master.sourceapp.SourceAppLoadGate
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.repository.SearchHistoryRepository
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

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
    /** 普通搜索快捷动作来自已提交配置，折叠搜索仍由页面禁用。 */
    private val clipSettingsRepository: ClipSettingsRepository,
    private val clipboardDataProcessor: DefaultClipboardDataProcessor,
    /** 普通/折叠搜索结果共用历史名称装配。 */
    private val sourceNames: ClipSourceNameAssembler,
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {
    /** 页面不可见时取消未完成名称查询，保留轻量结果等待复用。 */
    internal val sourceLoadGate = SourceAppLoadGate()


    /** 搜索页可见时才收集的快捷动作领域流，不在 ViewModel 初始化期启动 IO。 */
    val quickAction = clipSettingsRepository.quickAction
        .catch { error ->
            if (error is CancellationException) throw error
            // 数据源读失败时停用快捷动作，避免继续消费旧设置状态。
            logE("SearchViewModel", error) { "读取搜索快捷动作失败 reasonCode=settings_read_failed" }
            emit(ClipItemQuickAction.None)
        }

    companion object {
        /** 搜索历史提示防抖时间，单位毫秒；只影响历史表查询，不延迟剪贴内容搜索结果。 */
        private const val HISTORY_QUERY_DEBOUNCE_MS = 180L
    }

    /** 当前页唯一筛选状态；真实勾选与有效来源限制通过派生值区分。 */
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

    /** 显式重试会重建名称读取和 Pager，不依赖静态错误 PagingData 的 retry。 */
    private val _retryGeneration = MutableStateFlow(0)
    /** 页面把重试代次加入 collector key，确保错误后使用新会话。 */
    val retryGeneration: StateFlow<Int> = _retryGeneration

    /**
     * 来源 App 筛选候选项。
     *
     * 使用 stateIn 缓存最近一次列表，避免筛选弹窗每次展开都重新收集数据库 Flow。
     */
    val sourceApps: StateFlow<List<SourceAppCandidate>> = _retryGeneration.flatMapLatest {
        searchMenuCandidates { clipRepository.get().loadAllSourceApps() }
    }
        // 候选只含包名与历史名称，菜单可见行负责按需取图。
        .stateIn(
            CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /** 仅保存当前条件的缓存，切换字段/关键词时取消旧缓存任务。 */
    private val pagingSessions = SearchPagingSessions(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO))
    /** 为页面固定查询身份提供缓存流；只有结果态收集，构造本身不读取数据库。 */
    internal fun resultsFor(/** 页面与高亮共同持有的查询身份。 */ key: SearchResultKey): Flow<PagingData<ClipShowEntity>> =
        pagingSessions.get(key) {
            searchSourceNamePackages(key.conditions) {
                sourceLoadGate.observe { clipRepository.get().loadAllSourceApps() }
            }.flatMapLatest { matchedPackages ->
                /** 只在实际查询时计算窗口，避免保存过期时间戳。 */
                val timeRange = key.conditions.timeFilter.toTimeRange()
                Pager(config = clipPagingConfig()) {
                    clipRepository.get().searchClips(
                        userInput = key.conditions.query,
                        startTime = timeRange.startTime,
                        endTime = timeRange.endTime,
                        sourceAppPackages = key.conditions.sourceAppPackages,
                        visibilityScope = key.visibility,
                        fields = key.conditions.fields,
                        matchedSourcePackages = matchedPackages,
                    )
                }.flow.map { pagingData ->
                    /** 每个分页代次独立名称装配缓存，图标变化不参与此链路。 */
                    val session = sourceNames.newSession()
                    pagingData.map { clipDetail -> sourceLoadGate.run { session.attach(clipDetail.toUi()) } }
                }
            }.catchSearchLoadFailure()
        }

    /** 重试只更换查询代次，不清除输入、时间、来源或搜索字段。 */
    fun retrySearch() { _retryGeneration.update { it + 1 } }

    /** 字段切换只修改单一状态，保留时间/来源和输入；新结果态会使用独立缓存。 */
    fun toggleSearchField(/** 用户选择的字段范围。 */ target: ClipSearchField) {
        _filterState.update { it.copy(fields = SearchFieldSelectionReducer.toggle(it.fields, target)) }
    }

    /**
     * 当前范围的搜索历史。
     *
     * 历史提示和内容搜索刻意分成两条流：内容搜索继续即时响应输入，历史查询轻量防抖，避免每次键入都刷新历史面板。
     */
    val searchHistories: StateFlow<List<SearchHistoryData>> = combine(
        _filterState
            .map { it.query }
            .distinctUntilChanged()
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
        /** 输入换行规整沿用现有规则，不因菜单改造改变关键词。 */
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

    /** 即时切换全选，仅改变来源状态；候选暂空时忽略，时间和关键词保持不变。 */
    fun toggleAllSourceApps() {
        /** 操作时读取当前候选快照，事件基于 update 内最新勾选态归约，避免连续操作覆盖旧选择。 */
        val candidates = sourceApps.value.map { it.packageName }.toSet()
        _filterState.update { state ->
            state.copy(sourceAppSelection = SourceAppSelectionReducer.toggleAll(state.sourceAppSelection, candidates))
        }
    }

    /** 即时切换一个候选；空字符串是未知来源合法身份，不做空值过滤。 */
    fun toggleSourceApp(packageName: String) {
        /** 当前候选用于拦截已消失行的迟到点击，不丢弃显式选择中的缺失来源。 */
        val candidates = sourceApps.value.map { it.packageName }.toSet()
        _filterState.update { state ->
            state.copy(sourceAppSelection = SourceAppSelectionReducer.toggleItem(state.sourceAppSelection, candidates, packageName))
        }
    }

    /**
     * 提交当前搜索词并写入搜索历史。
     *
     * 保存只在明确提交时发生，输入过程不会污染历史；当前范围尚未初始化时忽略保存，避免误写到普通范围。
     */
    fun submitCurrentQuery() {
        /** 当前搜索范围；范围尚未初始化时不能把历史误写到普通或折叠的错误分组。 */
        val scope = visibilityScope.value ?: return
        /** 当前搜索框内容；空白搜索只用于展示全部匹配结果，不写历史。 */
        val query = _filterState.value.query
        if (query.trim().isBlank()) return
        viewModelScope.launch {
            searchHistoryRepository.get().saveHistory(scope, query)
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
