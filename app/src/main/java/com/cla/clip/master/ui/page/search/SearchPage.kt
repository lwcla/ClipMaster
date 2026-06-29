package com.cla.clip.master.ui.page.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.utils.displayName
import com.cla.clip.master.ui.dialog.ClipBatchDeleteChoiceDialog
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.widget.ClipBatchSelectionActionBar
import com.cla.clip.master.ui.widget.SingleChoiceDialog
import com.cla.clip.master.ui.widget.SingleChoiceOption
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.master.ui.widget.clip.ClipResultList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 剪贴搜索页。
 *
 * 页面负责搜索输入、筛选条件选择、Result/History 模式切换和结果展示；
 * 查询组合、分页和剪贴操作都交给 SearchViewModel，让 UI 层只编排页面内交互状态。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchPage(
    viewModel: SearchViewModel = hiltViewModel(),
    scope: SearchScope = SearchScope.VisibleOnly,
    onNavigate: (Route) -> Unit,
) {
    /** 保存态隔离 key；普通搜索和折叠搜索共用实现，但不能串滚动位置或顶部状态。 */
    val saveableScopeKey = scope.name
    /** 当前路由对应的数据层可见范围，用于驱动 ViewModel 切换普通/折叠搜索。 */
    val visibilityScope = scope.toVisibilityScope()
    /** 当前是否为普通可见数据搜索；折叠搜索会禁用快捷动作区并改用折叠时间展示。 */
    val isVisibleSearch = scope == SearchScope.VisibleOnly
    LaunchedEffect(visibilityScope) {
        viewModel.updateVisibilityScope(visibilityScope)
    }

    /** 搜索筛选状态，包含关键词、时间范围和来源 App 集合。 */
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    /** 当前可选来源 App 列表，用于来源筛选底部弹窗和已选展示文案。 */
    val sourceApps by viewModel.sourceApps.collectAsStateWithLifecycle()
    /** 来源包名到展示名的映射，统一复用来源 App 显示名兜底规则。 */
    val sourceAppDisplayNames = sourceApps.associate { sourceApp ->
        sourceApp.packageName to sourceApp.displayName()
    }
    /** 当前已选择来源 App 的可见名称列表，用于折叠搜索头里的来源选择器文案。 */
    val selectedSourceAppNames = remember(filterState.sourceAppPackages, sourceAppDisplayNames) {
        sourceAppDisplayNames.toSelectedSourceAppNames(
            selectedPackageNames = filterState.sourceAppPackages,
        )
    }
    /** 当前范围内按输入模糊匹配后的搜索历史。 */
    val searchHistories by viewModel.searchHistories.collectAsStateWithLifecycle()
    /** 页面内焦点管理器，用于上滑收起搜索头或提交搜索时关闭键盘。 */
    val focusManager = LocalFocusManager.current
    /** 页面级键盘控制器，用于首次进入拉起输入法以及提交搜索后主动收起输入法。 */
    val keyboardController = LocalSoftwareKeyboardController.current
    /** 搜索框焦点请求器，只在搜索页新实例首次进入时主动请求焦点。 */
    val searchFocusRequester = remember { FocusRequester() }
    /** 当前屏幕密度，用于把搜索头测量像素和列表内容 Dp 内边距互相转换。 */
    val density = LocalDensity.current
    /** 当前系统亮暗色状态，用于主题切换时只恢复搜索头稳定两态，不保留半拖拽偏移。 */
    val darkTheme = isSystemInDarkTheme()
    /** 协程作用域用于执行搜索头短吸附动画，不把动画状态放进 ViewModel。 */
    val coroutineScope = rememberCoroutineScope()
    /** 只有普通搜索范围响应快捷动作设置；折叠搜索保留整卡点击，避免和“取消折叠”管理语义混在一起。 */
    val quickAction by AppSetting.clipItemQuickActionFlow.collectAsStateWithLifecycle()
    /** 结果列表滚动状态需要跨亮暗色切换保存，并按搜索范围隔离。 */
    val resultListState = rememberSaveable(saveableScopeKey, saver = LazyListState.Saver) { LazyListState() }
    /** 历史列表滚动状态独立保存，避免 Result/History 模式互相重置位置。 */
    val historyListState = rememberSaveable(saveableScopeKey, saver = LazyListState.Saver) { LazyListState() }
    /** 当前页面模式；新搜索页实例默认 History，回栈恢复时保留用户已进入的 Result 模式。 */
    var pageMode by rememberSaveable(saveableScopeKey) { mutableStateOf(SearchPageMode.History) }
    /** 首次进入是否已请求过搜索框焦点；保存后可避免详情返回或配置变化重复强拉键盘。 */
    var initialFocusRequested by rememberSaveable(saveableScopeKey) { mutableStateOf(false) }
    /** 搜索头稳定吸附状态；只保存两态，不保存运行时像素偏移。 */
    var headerCollapseState by rememberSaveable(saveableScopeKey) {
        mutableStateOf(SearchHeaderCollapseState.Expanded)
    }
    /** 搜索头当前测量高度，单位像素；Collapsed 恢复时用最新高度换算偏移。 */
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    /** 搜索头运行时偏移，单位像素；拖拽中可以是中间态，但不会持久保存。 */
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    /** 搜索头吸附动画任务；新拖拽开始时取消旧任务，避免两个动画争抢偏移。 */
    var headerSnapJob by remember { mutableStateOf<Job?>(null) }
    /** 待删除的剪贴记录；非空时展示统一删除选择弹窗。 */
    var deleteClip by remember { mutableStateOf<ClipShowEntity?>(null) }
    /** 当前普通搜索结果选中的剪贴 id；折叠搜索第一版不读取该集合。 */
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    /** 当前是否处于搜索结果批量多选态；允许 0 选中时继续停留，方便用户重新选择。 */
    var selectionMode by remember { mutableStateOf(false) }
    /** 批量动作执行中标记；执行期间禁用底部按钮，避免重复弹窗或重复写库。 */
    var isBatchActionRunning by remember { mutableStateOf(false) }
    /** 是否显示批量删除选择弹窗；弹窗只展示数量，不展示搜索词或剪贴内容。 */
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    /** 来源选择弹窗显示状态，弹窗内部继续维护草稿选择。 */
    var showSourcePicker by remember { mutableStateOf(false) }
    /** 时间筛选单选弹窗显示状态；打开和取消都不改变历史/结果模式和列表滚动位置。 */
    var showTimeFilterDialog by remember { mutableStateOf(false) }
    /** 搜索框当前焦点状态，只用于键盘和进入历史模式，不直接决定历史面板生命周期。 */
    var searchBarFocused by remember { mutableStateOf(false) }
    /** 清空当前搜索范围历史的确认弹窗显示状态。 */
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    /** 状态栏顶部安全区高度，搜索头完全收起后列表仍需要避开系统状态栏。 */
    val statusBarTopPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    /** 系统导航栏底部安全区高度，页面不再依赖 Scaffold 后需要自行保护底部内容。 */
    val navigationBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    /** 输入法底部占位高度，历史列表需要避开它以免最后几条历史被键盘遮住。 */
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    /** 历史列表底部安全距离，输入法打开时优先按输入法高度保护内容。 */
    val historyBottomPadding = maxOf(navigationBarBottomPadding, imeBottomPadding)
    /** 当前内容顶部内边距，展开时跟随搜索头，收起时至少保留状态栏安全区。 */
    val contentTopPaddingDp = with(density) {
        max(statusBarTopPx, headerHeightPx + headerOffsetPx).toDp()
    }
    /** 当前选中数量；底部栏、批量弹窗和按钮可用性共用同一份派生值。 */
    val selectedCount = selectedIds.size
    /** 普通搜索结果是否正在展示多选态；折叠搜索第一版不接入批量删除和批量折叠。 */
    val clipSelectionMode = isVisibleSearch && selectionMode
    /** 批量操作按钮是否可用；0 选中或执行中都不能触发删除/折叠。 */
    val batchActionsEnabled = clipSelectionMode && selectedCount > 0 && !isBatchActionRunning

    /** 退出多选态并清空选择；返回键、查询条件变化和批量动作完成都复用这个出口。 */
    fun clearSelection() {
        selectionMode = false
        selectedIds = emptySet()
        isBatchActionRunning = false
        showBatchDeleteDialog = false
    }

    /** 长按普通搜索结果进入多选态，并把当前记录加入选中集合。 */
    fun enterSelection(clip: ClipShowEntity) {
        if (!isVisibleSearch) {
            return
        }
        selectionMode = true
        selectedIds = selectedIds + clip.id
    }

    /** 多选态点击 item 时切换选中状态；非普通搜索范围不会触发该选择语义。 */
    fun toggleSelection(clip: ClipShowEntity) {
        if (!isVisibleSearch) {
            return
        }
        selectedIds = if (clip.id in selectedIds) {
            selectedIds - clip.id
        } else {
            selectedIds + clip.id
        }
    }

    /**
     * 切到历史模式并展开搜索头。
     *
     * 用户进入搜索页、重新聚焦输入框、编辑关键词或清空关键词时调用；不依赖历史是否为空，保证空历史也覆盖结果。
     */
    fun showHistoryMode() {
        if (selectionMode) {
            clearSelection()
        }
        pageMode = SearchPageMode.History
        headerCollapseState = SearchHeaderCollapseState.Expanded
        headerOffsetPx = 0f
    }

    /**
     * 切到结果模式并按需要释放输入焦点。
     *
     * 只有提交搜索或点击历史项才调用；空关键词也允许进入结果模式，但历史仓库会忽略空白关键词保存。
     */
    fun showResultMode(clearInputFocus: Boolean = true) {
        pageMode = SearchPageMode.Result
        headerCollapseState = SearchHeaderCollapseState.Expanded
        headerOffsetPx = 0f
        if (clearInputFocus) {
            searchBarFocused = false
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = clipSelectionMode) {
        clearSelection()
    }

    /**
     * 将搜索头吸附到稳定两态。
     *
     * 只在 Result 模式执行；History 模式顶部固定展开，不参与吸附。
     */
    fun settleHeader(velocityY: Float = 0f) {
        if (pageMode != SearchPageMode.Result || headerHeightPx <= 0f) {
            return
        }
        /** 根据当前位置和 fling 方向得到最终稳定状态。 */
        val targetState = resolveSearchHeaderCollapseState(
            offsetPx = headerOffsetPx,
            headerHeightPx = headerHeightPx,
            velocityY = velocityY,
        )
        /** 稳定状态对应的目标像素偏移，Collapsed 始终按最新测量高度计算。 */
        val targetOffsetPx = when (targetState) {
            SearchHeaderCollapseState.Expanded -> 0f
            SearchHeaderCollapseState.Collapsed -> -headerHeightPx
        }
        headerSnapJob?.cancel()
        headerSnapJob = coroutineScope.launch {
            /** 每次吸附都从当前偏移创建动画，避免保存中间动画对象。 */
            val offsetAnimation = Animatable(headerOffsetPx)
            offsetAnimation.animateTo(
                targetValue = targetOffsetPx,
                animationSpec = tween(durationMillis = SEARCH_HEADER_SNAP_DURATION_MS),
            ) {
                headerOffsetPx = value
            }
            headerCollapseState = targetState
        }
    }

    /** Result 模式下的嵌套滚动连接，负责让搜索头先于结果列表收起或展开。 */
    val resultNestedScrollConnection = remember(pageMode, searchBarFocused, headerHeightPx) {
        object : NestedScrollConnection {
            /** 跟手滚动阶段只调整运行时偏移，不提前做两态吸附。 */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pageMode != SearchPageMode.Result || headerHeightPx <= 0f) {
                    return Offset.Zero
                }
                /** 当前垂直滚动增量，负值表示上滑，正值表示下滑。 */
                val deltaY = available.y
                if (deltaY == 0f) {
                    return Offset.Zero
                }
                if (deltaY < 0f && searchBarFocused) {
                    // 上滑开始收起搜索头时先释放输入焦点，避免输入框被移出屏幕后键盘仍占位。
                    searchBarFocused = false
                    focusManager.clearFocus()
                }
                /** 本次滚动前的搜索头偏移，用于计算父层实际消费了多少滚动距离。 */
                val previousOffsetPx = headerOffsetPx
                /** 本次滚动后的搜索头偏移，始终钳制在完全收起和完全展开之间。 */
                val nextOffsetPx = (headerOffsetPx + deltaY).coerceIn(-headerHeightPx, 0f)
                if (nextOffsetPx == previousOffsetPx) {
                    return Offset.Zero
                }
                headerSnapJob?.cancel()
                headerOffsetPx = nextOffsetPx
                when (nextOffsetPx) {
                    0f -> headerCollapseState = SearchHeaderCollapseState.Expanded
                    -headerHeightPx -> headerCollapseState = SearchHeaderCollapseState.Collapsed
                }
                return Offset(x = 0f, y = nextOffsetPx - previousOffsetPx)
            }

            /** fling 开始时根据速度方向安排最终吸附，但不抢走列表自己的 fling。 */
            override suspend fun onPreFling(available: Velocity): Velocity {
                /** 当前 fling 方向是否仍需要优先移动搜索头；需要时消费速度，避免列表和搜索头同时惯性滚动。 */
                val shouldConsumeVelocity = when {
                    available.y < 0f -> headerOffsetPx > -headerHeightPx
                    available.y > 0f -> headerOffsetPx < 0f
                    else -> false
                }
                settleHeader(velocityY = available.y)
                return if (shouldConsumeVelocity) available else Velocity.Zero
            }

            /** fling 完成后再兜底吸附一次，确保低速拖拽结束后不会停在半截。 */
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                /** 优先使用剩余速度，剩余速度为 0 时退回子列表已消费速度判断方向。 */
                val settleVelocityY = if (available.y != 0f) available.y else consumed.y
                settleHeader(velocityY = settleVelocityY)
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(darkTheme, headerHeightPx, headerCollapseState, pageMode) {
        /** 根据稳定状态和最新测量高度恢复运行时偏移，主题切换后不会沿用旧像素值。 */
        headerOffsetPx = when {
            pageMode == SearchPageMode.History -> 0f
            headerCollapseState == SearchHeaderCollapseState.Collapsed -> -headerHeightPx
            else -> 0f
        }
    }

    LaunchedEffect(saveableScopeKey) {
        if (!initialFocusRequested && pageMode == SearchPageMode.History) {
            /** 首次自动聚焦只在新搜索页实例执行；下一帧再请求焦点，确保 TextField 已挂载到焦点树。 */
            withFrameNanos { }
            searchFocusRequester.requestFocus()
            keyboardController?.show()
            initialFocusRequested = true
        }
    }

    LaunchedEffect(resultListState, pageMode, headerHeightPx) {
        snapshotFlow { resultListState.isScrollInProgress }.collect { isScrolling ->
            if (!isScrolling && pageMode == SearchPageMode.Result && headerOffsetPx !in listOf(0f, -headerHeightPx)) {
                /** 用户停止拖拽后触发短吸附，避免搜索头停在半截。 */
                settleHeader()
            }
        }
    }

    LaunchedEffect(visibilityScope, filterState.query, filterState.timeFilter, filterState.sourceAppPackages) {
        if (selectionMode) {
            clearSelection()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (pageMode == SearchPageMode.Result) {
                    Modifier.nestedScroll(resultNestedScrollConnection)
                } else {
                    Modifier
                }
            )
            .pointerInput(pageMode, headerHeightPx) {
                awaitEachGesture {
                    /** 首次按下只用于识别一次指针生命周期，不消费事件，避免影响列表或卡片手势。 */
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        /** 在 Final pass 观察抬手，确保子组件先处理点击、侧滑和列表滚动。 */
                        val pointerEvent = awaitPointerEvent(PointerEventPass.Final)
                    } while (pointerEvent.changes.any { pointerChange -> pointerChange.pressed })

                    if (pageMode == SearchPageMode.Result) {
                        /** 普通拖拽结束后兜底吸附，覆盖搜索头完全消费滚动而列表未进入滚动态的场景。 */
                        settleHeader()
                    }
                }
            }
    ) {
        if (pageMode == SearchPageMode.History) {
            SearchHistoryPanel(
                listState = historyListState,
                histories = searchHistories,
                query = filterState.query,
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = contentTopPaddingDp + 8.dp,
                    end = 12.dp,
                    bottom = historyBottomPadding + 24.dp,
                ),
                onHistoryClick = { history ->
                    viewModel.selectHistory(history.query)
                    showResultMode()
                },
                onDeleteHistory = viewModel::deleteHistory,
                onClearHistories = { showClearHistoryConfirm = true }
            )
        } else {
            /** 结果模式才收集分页数据，避免搜索页初始历史态提前加载剪贴数据列表。 */
            val pagedClips = viewModel.pagedClips.collectAsLazyPagingItems()

            ClipResultList(
                listState = resultListState,
                pagedClips = pagedClips,
                emptyText = stringResource(scope.emptyTextRes),
                highlightQuery = filterState.query,
                // 搜索结果和其他剪贴结果列表保持统一，同时把顶部留白交给折叠搜索头的当前可见高度。
                contentPadding = PaddingValues(
                    start = 10.dp,
                    top = contentTopPaddingDp + 10.dp,
                    end = 10.dp,
                    bottom = navigationBarBottomPadding + if (clipSelectionMode) 96.dp else 12.dp,
                ),
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
                enableQuickAction = isVisibleSearch && !clipSelectionMode && quickAction != ClipItemQuickAction.None,
                selectedIds = if (clipSelectionMode) selectedIds else emptySet(),
                selectionMode = clipSelectionMode,
                onToggleSelection = ::toggleSelection,
                onClick = { clip ->
                    if (clipSelectionMode) {
                        toggleSelection(clip)
                    } else {
                        onNavigate(DetailRoute(clip.id))
                    }
                },
                onLongClick = { clip -> enterSelection(clip) }
            )
        }

        SearchCollapsibleHeader(
            query = filterState.query,
            filterState = filterState,
            selectedSourceAppNames = selectedSourceAppNames,
            offsetPx = if (pageMode == SearchPageMode.History) 0f else headerOffsetPx,
            searchFieldModifier = Modifier.focusRequester(searchFocusRequester),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { size ->
                    /** 最新搜索头高度来自真实布局测量，Collapsed 恢复必须使用这次高度。 */
                    headerHeightPx = size.height.toFloat()
                },
            onQueryChange = { query ->
                viewModel.updateQuery(query)
                showHistoryMode()
            },
            onFocusChange = { focused ->
                searchBarFocused = focused
                if (focused) {
                    showHistoryMode()
                }
            },
            onSubmit = {
                viewModel.submitCurrentQuery()
                showResultMode()
            },
            onTimeClick = {
                showTimeFilterDialog = true
            },
            onSourceClick = {
                showSourcePicker = true
            }
        )

        if (clipSelectionMode) {
            ClipBatchSelectionActionBar(
                selectedText = stringResource(com.cla.clip.base.general.R.string.base_general_selected_count, selectedCount),
                deleteText = stringResource(com.cla.clip.base.general.R.string.base_general_delete),
                foldText = stringResource(com.cla.clip.base.general.R.string.base_general_fold_clip),
                enabled = batchActionsEnabled,
                modifier = Modifier.align(Alignment.BottomCenter),
                onDelete = { showBatchDeleteDialog = true },
                onFold = {
                    /** 本次要折叠的 id 快照；执行期间 UI 可退出多选，但数据库仍按这份快照处理。 */
                    val idsToFold = selectedIds
                    isBatchActionRunning = true
                    viewModel.foldVisibleClips(idsToFold) {
                        clearSelection()
                    }
                }
            )
        }
    }

    ClipDeleteChoiceDialog(
        clip = deleteClip,
        onDismiss = { deleteClip = null },
        onMoveToRecycleBin = viewModel::deleteClip,
        onDeletePermanently = viewModel::deleteClipPermanently
    )

    ClipBatchDeleteChoiceDialog(
        selectedCount = selectedCount,
        visible = showBatchDeleteDialog,
        onDismiss = { showBatchDeleteDialog = false },
        onMoveToRecycleBin = {
            /** 本次要移入回收站的 id 快照；弹窗不会读取或展示任何剪贴正文。 */
            val idsToDelete = selectedIds
            isBatchActionRunning = true
            viewModel.moveClipsToRecycleBin(idsToDelete) {
                clearSelection()
            }
        },
        onDeletePermanently = {
            /** 本次要彻底删除的 id 快照；Repository 会过滤无效、重复或已不可处理的记录。 */
            val idsToDelete = selectedIds
            isBatchActionRunning = true
            viewModel.deleteClipsPermanently(idsToDelete) {
                clearSelection()
            }
        }
    )

    if (showTimeFilterDialog) {
        SingleChoiceDialog(
            title = stringResource(com.cla.clip.base.general.R.string.base_general_time),
            options = SearchTimeFilter.entries.map { filter ->
                /** 时间筛选弹窗选项，标题和顶部选择器共用同一份本地化文案。 */
                SingleChoiceOption(
                    value = filter,
                    title = filter.labelText(),
                )
            },
            selectedValue = filterState.timeFilter,
            onSelect = { selectedFilter ->
                clearSelection()
                viewModel.updateTimeFilter(selectedFilter)
                showTimeFilterDialog = false
            },
            onDismiss = { showTimeFilterDialog = false },
            dismissText = stringResource(com.cla.clip.base.general.R.string.base_general_cancel),
        )
    }

    if (showSourcePicker) {
        SourceAppPickerSheet(
            sourceApps = sourceApps,
            selectedPackageNames = filterState.sourceAppPackages,
            onDismiss = { showSourcePicker = false },
            onConfirm = { selectedPackageNames ->
                clearSelection()
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
                showHistoryMode()
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

/** 搜索页当前展示模式，明确区分结果列表折叠链路和历史列表固定顶部链路。 */
private enum class SearchPageMode {
    /** 结果模式，搜索头跟随结果列表滚动优先收起或展开。 */
    Result,

    /** 历史模式，搜索头固定展开，历史列表独立滚动。 */
    History,
}

/** 搜索头吸附动画时长，单位毫秒；短动画只负责归位，不承担额外动效表现。 */
private const val SEARCH_HEADER_SNAP_DURATION_MS = 140
