package com.cla.clip.master.ui.page.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.utils.displayName
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.master.ui.widget.clip.ClipResultList

/**
 * 搜索页顶部控件响应结果列表滚动方向的最小位移。
 *
 * 这里使用像素级阈值过滤嵌套滚动中的 0 值和极小抖动；真正的用户上滑/下滑会进入连续折叠或展开流程。
 */
private const val SEARCH_CONTROLS_SCROLL_THRESHOLD_PX = 1f

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
