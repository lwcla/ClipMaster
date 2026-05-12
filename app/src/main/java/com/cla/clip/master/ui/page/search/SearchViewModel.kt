package com.cla.clip.master.ui.page.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.map
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.entity.toUi
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.master.processor.ClipboardDataProcessor
import com.cla.clip.master.processor.DefaultClipboardDataProcessor
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /** 当前选择的来源 App 包名；为 null 表示不过滤来源。 */
    val sourceAppPackage: String? = null,
)

/**
 * 搜索页 ViewModel。
 *
 * 负责把关键词、时间范围和来源 App 组合成 Paging 查询，同时复用剪贴数据处理器提供的复制、删除和置顶能力。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val clipRepository: Lazy<ClipRepository>,
    private val clipboardDataProcessor: DefaultClipboardDataProcessor,
) : ViewModel(), ClipboardDataProcessor by clipboardDataProcessor {

    private val _filterState = MutableStateFlow(SearchFilterState())

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
    val pagedClips = _filterState.flatMapLatest { state ->
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
                sourceAppPackage = state.sourceAppPackage
            )
        }.flow.map { pagingData ->
            pagingData.map { clipDetail -> clipDetail.toUi() }
        }
    }.cachedIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)
    )

    /**
     * 当前来源 App 的展示名。
     *
     * UI 使用它显示筛选 Chip 文案；当来源列表尚未加载或对应 App 被清理时，回退到包名，避免筛选状态丢失。
     */
    val selectedSourceAppName: StateFlow<String?> = combine(_filterState, sourceApps) { state, apps ->
        val packageName = state.sourceAppPackage ?: return@combine null
        apps.firstOrNull { it.packageName == packageName }?.appName?.takeIf { it.isNotBlank() } ?: packageName
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    /** 更新搜索关键词，输入变化后会自动触发新的分页查询。 */
    fun updateQuery(query: String) {
        _filterState.update { it.copy(query = query) }
    }

    /** 更新时间筛选条件。 */
    fun updateTimeFilter(filter: SearchTimeFilter) {
        _filterState.update { it.copy(timeFilter = filter) }
    }

    /** 更新来源 App 筛选条件；传 null 表示恢复全部来源。 */
    fun updateSourceApp(packageName: String?) {
        _filterState.update { it.copy(sourceAppPackage = packageName) }
    }
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
