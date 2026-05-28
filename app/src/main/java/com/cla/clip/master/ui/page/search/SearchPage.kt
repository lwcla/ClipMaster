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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.cla.clip.master.ui.widget.SecondaryPageScaffold
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.master.ui.widget.clip.ClipResultList

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
    LaunchedEffect(visibilityScope) {
        viewModel.updateVisibilityScope(visibilityScope)
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

    BackHandler(enabled = showHistoryPanel) {
        // 历史面板是搜索页内部的临时覆盖层，返回键先关闭它并释放焦点，再由下一次返回退出页面。
        searchBarFocused = false
        focusManager.clearFocus()
    }

    SecondaryPageScaffold(
        title = stringResource(scope.titleRes),
        onBack = onBack
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
                        // 搜索结果和其他剪贴结果列表保持统一，只保留一层明确但不过分的轻量底部留白。
                        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
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
