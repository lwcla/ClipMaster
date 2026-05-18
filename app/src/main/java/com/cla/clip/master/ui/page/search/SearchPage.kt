package com.cla.clip.master.ui.page.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.dao.SearchHistoryData
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.utils.displayName
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.page.list.ClipCardTimeMode
import com.cla.clip.master.ui.page.list.ClipResultList
import com.cla.clip.master.ui.widget.TitleBar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 搜索页顶部控件响应结果列表滚动方向的最小位移。
 *
 * 这里使用像素级阈值过滤嵌套滚动中的 0 值和极小抖动；真正的用户上滑/下滑会进入连续折叠或展开流程。
 */
private const val SEARCH_CONTROLS_SCROLL_THRESHOLD_PX = 1f

/**
 * 搜索输入框的固定单行高度。
 *
 * `OutlinedTextField` 默认会根据输入内容和字体测量高度；搜索页顶部空间有限，这里固定为 Material 单行输入框常用高度，
 * 避免粘贴多行内容或异常测量时把下方结果列表挤小。
 */
private val SEARCH_BAR_FIELD_HEIGHT = 56.dp

/**
 * 来源 App 弹窗展开后的最大高度比例。
 *
 * 使用屏幕高度比例而不是固定 dp，避免大屏或横屏时弹窗仍停留在偏矮高度；如果后续体验需要微调，只改比例。
 */
private const val SOURCE_APP_SHEET_MAX_HEIGHT_RATIO = 0.86f

/**
 * 剪贴搜索页。
 *
 * 页面负责搜索输入、筛选条件选择和结果展示；查询组合、分页和剪贴操作都交给 SearchViewModel，
 * 让 UI 层保持轻量，后续增加自定义日期或搜索历史时也能继续沿用这条状态流。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(
    viewModel: SearchViewModel = hiltViewModel(),
    scope: SearchScope = SearchScope.VisibleOnly,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val visibilityScope = scope.toVisibilityScope()
    val isVisibleSearch = scope == SearchScope.VisibleOnly
    // 搜索框和筛选区属于搜索页自己的顶部控制层；这里记录已折叠距离，列表只在折叠区到达边界后继续滚动。
    var searchControlsCollapsePx by remember { mutableStateOf(0f) }
    // 折叠区完整展开时的真实高度，用于把滚动距离转换为 AppBarLayout 式的连续收起/展开进度。
    var searchControlsHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(visibilityScope) {
        viewModel.updateVisibilityScope(visibilityScope)
        // 搜索范围切换时先恢复筛选入口，避免用户进入折叠搜索后看不到当前查询条件。
        searchControlsCollapsePx = 0f
    }
    LaunchedEffect(searchControlsHeightPx) {
        if (searchControlsHeightPx > 0) {
            // 来源 App 标签或筛选内容高度变化时，把旧折叠距离限制到新的高度范围内，避免出现负高度或无法完全展开。
            searchControlsCollapsePx = searchControlsCollapsePx.coerceIn(0f, searchControlsHeightPx.toFloat())
        }
    }

    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val sourceApps by viewModel.sourceApps.collectAsStateWithLifecycle()
    val sourceAppDisplayNames = sourceApps.associate { sourceApp ->
        sourceApp.packageName to sourceApp.displayName()
    }
    val selectedSourceAppNames = remember(filterState.sourceAppPackages, sourceAppDisplayNames) {
        sourceAppDisplayNames.toSelectedSourceAppNames(
            selectedPackageNames = filterState.sourceAppPackages,
        )
    }
    val searchHistories by viewModel.searchHistories.collectAsStateWithLifecycle()
    val pagedClips = viewModel.pagedClips.collectAsLazyPagingItems()
    val focusManager = LocalFocusManager.current
    // 只有普通搜索范围响应快捷动作设置；折叠搜索保留整卡点击，避免和“取消折叠”管理语义混在一起。
    val quickAction by AppSetting.clipItemQuickActionFlow.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var searchBarFocused by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    val showHistoryPanel = searchBarFocused && searchHistories.isNotEmpty()

    LaunchedEffect(showHistoryPanel) {
        if (showHistoryPanel) {
            // 历史覆盖结果区时固定展开搜索框和筛选器，避免顶部控件折叠后用户看不到当前输入上下文。
            searchControlsCollapsePx = 0f
        }
    }

    BackHandler(enabled = showHistoryPanel) {
        // 历史面板是搜索页内部的临时覆盖层，返回键先关闭它并释放焦点，再由下一次返回退出页面。
        searchBarFocused = false
        focusManager.clearFocus()
    }
    val searchControlsScrollConnection = remember {
        object : NestedScrollConnection {
            /**
             * 在结果列表消费滚动前优先处理搜索控件折叠区。
             *
             * 上滑先把搜索框和筛选区连续收起；下滑先把它们连续展开。只有折叠区到达边界后，剩余滚动才交给列表，
             * 这样行为更接近 View 系统里的 AppBarLayout，而不是一次性隐藏顶部控件。
             */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val maxCollapsePx = searchControlsHeightPx.toFloat()
                if (maxCollapsePx <= 0f) {
                    return Offset.Zero
                }

                return when {
                    available.y < -SEARCH_CONTROLS_SCROLL_THRESHOLD_PX && searchControlsCollapsePx < maxCollapsePx -> {
                        val previousCollapsePx = searchControlsCollapsePx
                        searchControlsCollapsePx = (searchControlsCollapsePx - available.y).coerceAtMost(maxCollapsePx)
                        val consumedCollapsePx = searchControlsCollapsePx - previousCollapsePx
                        Offset(x = 0f, y = -consumedCollapsePx)
                    }

                    available.y > SEARCH_CONTROLS_SCROLL_THRESHOLD_PX && searchControlsCollapsePx > 0f -> {
                        val previousCollapsePx = searchControlsCollapsePx
                        searchControlsCollapsePx = (searchControlsCollapsePx - available.y).coerceAtLeast(0f)
                        val consumedExpandPx = previousCollapsePx - searchControlsCollapsePx
                        Offset(x = 0f, y = consumedExpandPx)
                    }

                    else -> Offset.Zero
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TitleBar(
                title = stringResource(scope.titleRes),
                onBack = onBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            CollapsibleSearchControls(
                collapseOffsetPx = searchControlsCollapsePx,
                expandedHeightPx = searchControlsHeightPx,
                onExpandedHeightChange = { measuredHeightPx ->
                    if (measuredHeightPx > 0 && searchControlsHeightPx != measuredHeightPx) {
                        searchControlsHeightPx = measuredHeightPx
                    }
                }
            ) {
                Column {
                    SearchBar(
                        query = filterState.query,
                        onQueryChange = viewModel::updateQuery,
                        onFocusChange = { searchBarFocused = it },
                        onSubmit = {
                            viewModel.submitCurrentQuery()
                            searchBarFocused = false
                            focusManager.clearFocus()
                        }
                    )

                    SearchFilters(
                        filterState = filterState,
                        selectedSourceAppNames = selectedSourceAppNames,
                        onTimeFilterChange = viewModel::updateTimeFilter,
                        onSourceClick = {
                            showSourcePicker = true
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // 历史面板显示时不让结果区滚动折叠顶部控件，保证历史选择过程里搜索条件入口稳定可见。
                        if (showHistoryPanel) Modifier else Modifier.nestedScroll(searchControlsScrollConnection)
                    )
                    // 结果区占用搜索框和筛选器下方的剩余高度，避免列表在 Column 中抢占顶部控件空间。
                    .weight(1f)
            ) {
                if (showHistoryPanel) {
                    SearchHistoryPanel(
                        histories = searchHistories,
                        query = filterState.query,
                        onHistoryClick = { history ->
                            viewModel.selectHistory(history.query)
                            searchBarFocused = false
                            focusManager.clearFocus()
                        },
                        onDeleteHistory = viewModel::deleteHistory,
                        onClearHistories = { showClearHistoryConfirm = true }
                    )
                } else {
                    ClipResultList(
                        listState = listState,
                        pagedClips = pagedClips,
                        emptyText = stringResource(scope.emptyTextRes),
                        highlightQuery = filterState.query,
                        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 24.dp),
                        // 折叠搜索保留置顶操作能力；数据层会先排置顶数据，再在分组内按 foldedAt 倒序。
                        onPinToggle = { clip -> viewModel.updatePinStatus(clip, !clip.isPinned) },
                        onDelete = { deleteClip = it },
                        onCopy = viewModel::copyToClipboard,
                        onSwipePastAction = { clip ->
                            viewModel.updateFoldStatus(
                                clip = clip,
                                isFolded = scope == SearchScope.VisibleOnly
                            )
                        },
                        swipePastActionText = stringResource(scope.swipePastTextRes),
                        timeMode = if (isVisibleSearch) ClipCardTimeMode.ClipTime else ClipCardTimeMode.FoldedTime,
                        quickAction = quickAction,
                        enableQuickAction = isVisibleSearch && quickAction != ClipItemQuickAction.None,
                        onClick = { onNavigate(DetailRoute(it.id)) },
                        onLongClick = {}
                    )
                }
            }
        }
    }

    ClipDeleteChoiceDialog(
        clip = deleteClip,
        onDismiss = { deleteClip = null },
        onMoveToRecycleBin = viewModel::deleteClip,
        onDeletePermanently = viewModel::deleteClipPermanently
    )

    if (showSourcePicker) {
        SourceAppPickerSheet(
            sourceApps = sourceApps,
            selectedPackageNames = filterState.sourceAppPackages,
            onDismiss = { showSourcePicker = false },
            onConfirm = { selectedPackageNames ->
                viewModel.updateSourceApps(selectedPackageNames)
                showSourcePicker = false
            }
        )
    }

    if (showClearHistoryConfirm) {
        ClearSearchHistoryDialog(
            onDismiss = { showClearHistoryConfirm = false },
            onConfirm = {
                viewModel.clearCurrentScopeHistory()
                showClearHistoryConfirm = false
            }
        )
    }
}

/** 将搜索路由范围转换为数据层查询范围，页面层只负责连接导航参数和 ViewModel 查询。 */
private fun SearchScope.toVisibilityScope(): ClipVisibilityScope {
    return when (this) {
        SearchScope.VisibleOnly -> ClipVisibilityScope.VisibleOnly
        SearchScope.FoldedOnly -> ClipVisibilityScope.FoldedOnly
    }
}

/** 搜索标题随范围变化，折叠搜索复用同一页面但需要给用户明确上下文。 */
private val SearchScope.titleRes: Int
    get() = when (this) {
        SearchScope.VisibleOnly -> com.cla.clip.base.general.R.string.base_general_search
        SearchScope.FoldedOnly -> com.cla.clip.base.general.R.string.base_general_search_folded_clips
    }

/** 搜索空态随范围区分，避免折叠搜索无结果被误解为没有折叠数据。 */
private val SearchScope.emptyTextRes: Int
    get() = when (this) {
        SearchScope.VisibleOnly -> com.cla.clip.base.general.R.string.base_general_search_result_empty
        SearchScope.FoldedOnly -> com.cla.clip.base.general.R.string.base_general_folded_search_result_empty
    }

/** 继续右滑提示随范围变化，普通搜索折叠数据，折叠搜索取消折叠数据。 */
private val SearchScope.swipePastTextRes: Int
    get() = when (this) {
        SearchScope.VisibleOnly -> com.cla.clip.base.general.R.string.base_general_continue_swipe_to_fold_clip
        SearchScope.FoldedOnly -> com.cla.clip.base.general.R.string.base_general_continue_swipe_to_unfold_clip
    }

/**
 * 搜索框和筛选区的 AppBarLayout 式折叠容器。
 *
 * 容器先在保持父级宽度约束的前提下放宽高度，测量完整内容；再用自身可见高度和裁剪表现折叠进度。
 * 这样搜索框和筛选区即使已经折叠到 0，也仍能保留完整高度基准，避免列表滚动反向污染顶部控件测量。
 */
@Composable
private fun CollapsibleSearchControls(
    collapseOffsetPx: Float,
    expandedHeightPx: Int,
    onExpandedHeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 子节点按完整高度测量后再回写给父层折叠状态；这是顶部控件高度变化时的唯一事实来源。
                    .onSizeChanged { size ->
                        if (size.height > 0 && size.height != expandedHeightPx) {
                            onExpandedHeightChange(size.height)
                        }
                    }
            ) {
                content()
            }
        }
    ) { measurables, constraints ->
        val measurable = measurables.firstOrNull()
        val placeable = measurable?.measure(
            constraints.copy(
                minHeight = 0,
                // 只放宽高度，宽度仍沿用父级约束，避免搜索框在无界宽度下失去 fillMaxWidth 行为。
                maxHeight = Constraints.Infinity
            )
        )
        val contentHeightPx = placeable?.height ?: 0
        val collapseBaseHeightPx = if (expandedHeightPx > 0) expandedHeightPx else contentHeightPx
        val normalizedCollapsePx = if (collapseBaseHeightPx > 0) {
            collapseOffsetPx.coerceIn(0f, collapseBaseHeightPx.toFloat())
        } else {
            0f
        }
        val visibleHeightPx = (collapseBaseHeightPx - normalizedCollapsePx.roundToInt()).coerceAtLeast(0)
        val layoutWidth = (placeable?.width ?: constraints.minWidth).coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = visibleHeightPx.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            placeable?.placeRelative(x = 0, y = -normalizedCollapsePx.roundToInt())
        }
    }
}

/**
 * 搜索输入框。
 *
 * 只承载单行关键词输入；输入规整在 ViewModel 层完成，这里通过固定高度和单行参数保证顶部控件高度稳定。
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(min = SEARCH_BAR_FIELD_HEIGHT, max = SEARCH_BAR_FIELD_HEIGHT)
            .onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                onSubmit()
            }
        ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_keyword)
                    )
                }
            }
        },
        placeholder = {
            Text(stringResource(com.cla.clip.base.general.R.string.base_general_search_clip_hint))
        }
    )
}

/**
 * 搜索筛选区。
 *
 * 时间筛选使用 FilterChip 表达互斥选项，来源筛选单独用 AssistChip 打开弹窗，
 * 这样固定选项和动态 App 列表不会挤在同一个横向区域里。
 */
@Composable
private fun SearchFilters(
    filterState: SearchFilterState,
    selectedSourceAppNames: List<String>,
    onTimeFilterChange: (SearchTimeFilter) -> Unit,
    onSourceClick: () -> Unit,
) {
    val selectedSourceAppLabel = when (selectedSourceAppNames.size) {
        0 -> stringResource(com.cla.clip.base.general.R.string.base_general_all_source_apps)
        1 -> selectedSourceAppNames.first()
        else -> stringResource(
            com.cla.clip.base.general.R.string.base_general_selected_source_app_count,
            selectedSourceAppNames.size
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 固定时间选项在窄屏上可能横向放不下，允许轻量横滑比压缩文字更可读。
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeFilterChip(SearchTimeFilter.ALL, filterState.timeFilter, onTimeFilterChange)
            TimeFilterChip(SearchTimeFilter.TODAY, filterState.timeFilter, onTimeFilterChange)
            TimeFilterChip(SearchTimeFilter.LAST_7_DAYS, filterState.timeFilter, onTimeFilterChange)
            TimeFilterChip(SearchTimeFilter.LAST_30_DAYS, filterState.timeFilter, onTimeFilterChange)
        }

        AssistChip(
            onClick = onSourceClick,
            label = {
                Text(
                    text = selectedSourceAppLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null
                )
            }
        )
    }
}

/** 单个时间筛选 Chip。 */
@Composable
private fun TimeFilterChip(
    filter: SearchTimeFilter,
    selectedFilter: SearchTimeFilter,
    onClick: (SearchTimeFilter) -> Unit,
) {
    FilterChip(
        selected = filter == selectedFilter,
        onClick = { onClick(filter) },
        label = {
            Text(
                text = filter.labelText(),
                maxLines = 1
            )
        }
    )
}

/** 时间筛选对应的本地化展示文案。 */
@Composable
private fun SearchTimeFilter.labelText(): String {
    return when (this) {
        SearchTimeFilter.ALL -> stringResource(com.cla.clip.base.general.R.string.base_general_all_time)
        SearchTimeFilter.TODAY -> stringResource(com.cla.clip.base.general.R.string.base_general_today)
        SearchTimeFilter.LAST_7_DAYS -> stringResource(com.cla.clip.base.general.R.string.base_general_last_7_days)
        SearchTimeFilter.LAST_30_DAYS -> stringResource(com.cla.clip.base.general.R.string.base_general_last_30_days)
    }
}

/**
 * 搜索历史覆盖面板。
 *
 * 只有搜索框聚焦且当前范围存在匹配历史时才展示；面板临时替换结果区，不改变当前搜索结果 Paging 流，
 * 用户收起面板后会继续看到原来的结果和筛选状态。
 */
@Composable
private fun SearchHistoryPanel(
    histories: List<SearchHistoryData>,
    query: String,
    onHistoryClick: (SearchHistoryData) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistories: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(com.cla.clip.base.general.R.string.base_general_search_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearHistories) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history))
                    }
                }
            }

            items(
                items = histories,
                key = { it.id }
            ) { history ->
                SearchHistoryRow(
                    history = history,
                    query = query,
                    onClick = { onHistoryClick(history) },
                    onDelete = { onDeleteHistory(history.id) }
                )
            }
        }
    }
}

/**
 * 单条搜索历史。
 *
 * 点击整行会恢复关键词并保存更新时间；删除按钮只删除历史本身，不触发搜索框清空或筛选重置。
 */
@Composable
private fun SearchHistoryRow(
    history: SearchHistoryData,
    query: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = history.query.highlightHistoryMatch(query),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_delete_search_history),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 清空当前范围搜索历史确认框。
 *
 * 清空动作只作用于当前普通/折叠搜索范围；确认框避免用户误删一组仍有使用价值的历史提示。
 */
@Composable
private fun ClearSearchHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history_title)) },
        text = { Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_history))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
            }
        }
    )
}

/**
 * 为历史项高亮当前输入命中的片段。
 *
 * 历史匹配按规范化后的包含关系执行，这里只做可见文本上的大小写不敏感高亮；查询为空时不加样式。
 */
@Composable
private fun String.highlightHistoryMatch(query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface
    return remember(this, query, highlightColor, highlightTextColor) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (isBlank() || normalizedQuery.isBlank()) {
            return@remember AnnotatedString(this)
        }

        val normalizedText = lowercase(Locale.ROOT)
        val highlightedRanges = mutableListOf<IntRange>()
        buildAnnotatedString {
            append(this@highlightHistoryMatch)
            var startIndex = normalizedText.indexOf(normalizedQuery)
            while (startIndex >= 0) {
                val endExclusive = startIndex + normalizedQuery.length
                val range = startIndex until endExclusive
                val hasOverlap = highlightedRanges.any { existing ->
                    range.first <= existing.last && range.last >= existing.first
                }
                if (!hasOverlap) {
                    addStyle(
                        style = SpanStyle(
                            color = highlightTextColor,
                            background = highlightColor
                        ),
                        start = startIndex,
                        end = endExclusive
                    )
                    highlightedRanges += range
                }
                startIndex = normalizedText.indexOf(normalizedQuery, startIndex + normalizedQuery.length)
            }
        }
    }
}

/**
 * 来源 App 选择弹窗。
 *
 * 标题右侧提供“全选”批量控制，列表只展示具体来源 App；弹窗内部草稿允许临时 0 选中，
 * 但 0 选中不能提交到外部筛选状态，避免破坏空集合表示“全部来源”的数据契约。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceAppPickerSheet(
    sourceApps: List<SourceAppData>,
    selectedPackageNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val sheetMaxHeight = configuration.screenHeightDp.dp * SOURCE_APP_SHEET_MAX_HEIGHT_RATIO
    val allPackageNames = remember(sourceApps) {
        // 只把当前候选列表里的包名参与全选联动；候选缺失的旧筛选包名会保留到外部 Chip，但不出现在弹窗内。
        sourceApps.map { it.packageName }.toSet()
    }
    var draftSelection by remember(selectedPackageNames, allPackageNames) {
        mutableStateOf(
            SourceAppDraftSelection.fromAppliedSelection(
                selectedPackageNames = selectedPackageNames,
                allPackageNames = allPackageNames
            )
        )
    }
    val isAllSelected = draftSelection.isAllSelected(allPackageNames)
    val selectedCount = draftSelection.selectedCount(allPackageNames)
    val canConfirm = sourceApps.isEmpty() || selectedCount > 0
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .padding(bottom = 24.dp)
        ) {
            SourceAppPickerTitle(
                checked = isAllSelected,
                enabled = sourceApps.isNotEmpty(),
                onCheckedChange = {
                    draftSelection = if (isAllSelected) {
                        SourceAppDraftSelection.Explicit(emptySet())
                    } else {
                        SourceAppDraftSelection.All
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    // 来源 App 数量较多时，列表只占用弹窗剩余空间，把底部确认按钮稳定留在可点击区域。
                    .weight(1f, fill = false)
            ) {
                items(
                    items = sourceApps,
                    key = { it.packageName }
                ) { sourceApp ->
                    val rowSelected = draftSelection.contains(
                        packageName = sourceApp.packageName,
                        allPackageNames = allPackageNames
                    )
                    SourceAppRow(
                        title = sourceApp.displayName(),
                        subtitle = sourceApp.packageName,
                        selected = rowSelected,
                        onClick = {
                            draftSelection = if (rowSelected) {
                                draftSelection.remove(
                                    packageName = sourceApp.packageName,
                                    allPackageNames = allPackageNames
                                )
                            } else {
                                draftSelection.add(
                                    packageName = sourceApp.packageName,
                                    allPackageNames = allPackageNames
                                )
                            }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!canConfirm) {
                    Text(
                        text = stringResource(
                            com.cla.clip.base.general.R.string.base_general_selected_source_app_count,
                            0
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_cancel))
                }
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = canConfirm,
                    onClick = { onConfirm(draftSelection.toAppliedPackageNames(allPackageNames)) }
                ) {
                    Text(stringResource(com.cla.clip.base.general.R.string.base_general_sure))
                }
            }
        }
    }
}

/**
 * 来源 App 弹窗标题和全选控制。
 *
 * “全选”属于批量操作，不再作为列表里的普通来源项；使用普通 Checkbox 而不是三态组件，
 * 让部分选中和 0 选中都表现为未全选，避免用户误解点击后的方向。
 */
@Composable
private fun SourceAppPickerTitle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(com.cla.clip.base.general.R.string.base_general_source_app),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Row(
            modifier = Modifier.clickable(enabled = enabled, onClick = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = { onCheckedChange() }
            )
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_select_all),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }
    }
}

/**
 * 生成当前已选来源 App 的展示名称列表。
 *
 * 来源名称由共享工具统一规整；数据库里仍可能只有包名，这类缺失候选继续回退包名，
 * 方便用户识别历史筛选条件。
 */
private fun Map<String, String>.toSelectedSourceAppNames(
    selectedPackageNames: Set<String>,
): List<String> {
    if (selectedPackageNames.isEmpty()) {
        return emptyList()
    }

    val appNames = filterKeys { packageName -> packageName in selectedPackageNames }
        .values
        .toList()
    val missingPackageNames = selectedPackageNames
        .filterNot { packageName -> containsKey(packageName) }
        .sorted()
    return appNames + missingPackageNames
}

/**
 * 来源 App 弹窗内的草稿选择状态。
 *
 * 外部筛选用空集合表达“全部来源”，但弹窗内部还需要表达“临时 0 个来源被选中”的状态；
 * 因此这里显式区分 All 和 Explicit，避免把 0 选中误提交成全部来源。
 */
private sealed interface SourceAppDraftSelection {

    /** 全部来源模式，提交时会转换为空集合，沿用搜索筛选层“不过滤来源”的契约。 */
    data object All : SourceAppDraftSelection

    /** 显式选择的来源包名集合；允许临时为空，但为空时不能点击确定。 */
    data class Explicit(val packageNames: Set<String>) : SourceAppDraftSelection

    companion object {
        /**
         * 根据外部已应用筛选创建弹窗草稿。
         *
         * 外部空集合代表全部来源；非空集合只保留当前候选来源中的包名，避免已消失候选影响弹窗全选状态。
         */
        fun fromAppliedSelection(
            selectedPackageNames: Set<String>,
            allPackageNames: Set<String>,
        ): SourceAppDraftSelection {
            if (selectedPackageNames.isEmpty()) {
                return All
            }
            val visibleSelectedPackages = selectedPackageNames.intersect(allPackageNames)
            return normalizeExplicitSelection(
                selectedPackageNames = visibleSelectedPackages,
                allPackageNames = allPackageNames
            )
        }
    }
}

/** 判断草稿是否覆盖当前所有来源候选，空候选时仍按全部来源处理。 */
private fun SourceAppDraftSelection.isAllSelected(allPackageNames: Set<String>): Boolean {
    return when (this) {
        SourceAppDraftSelection.All -> true
        is SourceAppDraftSelection.Explicit -> allPackageNames.isNotEmpty() && packageNames.containsAll(allPackageNames)
    }
}

/** 计算弹窗内当前选中数量；全部来源模式下显示为当前候选数量。 */
private fun SourceAppDraftSelection.selectedCount(allPackageNames: Set<String>): Int {
    return when (this) {
        SourceAppDraftSelection.All -> allPackageNames.size
        is SourceAppDraftSelection.Explicit -> packageNames.size
    }
}

/** 判断某个具体来源行是否在草稿中被选中。 */
private fun SourceAppDraftSelection.contains(
    packageName: String,
    allPackageNames: Set<String>,
): Boolean {
    return when (this) {
        SourceAppDraftSelection.All -> packageName in allPackageNames
        is SourceAppDraftSelection.Explicit -> packageName in packageNames
    }
}

/**
 * 从草稿中移除一个来源。
 *
 * 如果当前是全部来源，先展开成当前全部候选包名再移除目标包名；这是“全选后取消单个”的核心联动。
 */
private fun SourceAppDraftSelection.remove(
    packageName: String,
    allPackageNames: Set<String>,
): SourceAppDraftSelection {
    val explicitPackages = when (this) {
        SourceAppDraftSelection.All -> allPackageNames
        is SourceAppDraftSelection.Explicit -> packageNames
    }
    return SourceAppDraftSelection.Explicit(explicitPackages - packageName)
}

/**
 * 向草稿中加入一个来源。
 *
 * 如果加入后覆盖当前所有候选，自动归一回 All，让标题右侧“全选”和外部空集合语义重新对齐。
 */
private fun SourceAppDraftSelection.add(
    packageName: String,
    allPackageNames: Set<String>,
): SourceAppDraftSelection {
    val explicitPackages = when (this) {
        SourceAppDraftSelection.All -> allPackageNames
        is SourceAppDraftSelection.Explicit -> packageNames
    } + packageName
    return normalizeExplicitSelection(
        selectedPackageNames = explicitPackages,
        allPackageNames = allPackageNames
    )
}

/** 将草稿转换成外部筛选可接收的包名集合；All 始终提交为空集合。 */
private fun SourceAppDraftSelection.toAppliedPackageNames(allPackageNames: Set<String>): Set<String> {
    return when (this) {
        SourceAppDraftSelection.All -> emptySet()
        is SourceAppDraftSelection.Explicit -> packageNames.intersect(allPackageNames)
    }
}

/** 显式集合覆盖所有候选时自动归一成 All，避免外部保存一份等价但更重的全量包名集合。 */
private fun normalizeExplicitSelection(
    selectedPackageNames: Set<String>,
    allPackageNames: Set<String>,
): SourceAppDraftSelection {
    return if (allPackageNames.isNotEmpty() && selectedPackageNames.containsAll(allPackageNames)) {
        SourceAppDraftSelection.All
    } else {
        SourceAppDraftSelection.Explicit(selectedPackageNames)
    }
}

/** 来源 App 弹窗中的单行选项。 */
@Composable
private fun SourceAppRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() }
        )
    }
}
