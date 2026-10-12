package com.cla.clip.master.ui.page.search

import com.cla.clip.master.ui.widget.sourceapp.rememberAppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.SourceAppNameLoadingEffect
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.paging.compose.collectAsLazyPagingItems
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.utils.toSourceAppDisplayName
import com.cla.clip.master.ui.dialog.ClipBatchDeleteChoiceDialog
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.navigation.DetailRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.navigation.SearchScope
import com.cla.clip.master.ui.widget.ClipBatchSelectionActionBar
import com.cla.clip.master.ui.widget.clip.ClipCardTimeMode
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ui.widget.clip.ClipListAdConfig
import com.cla.clip.master.ui.widget.clip.ClipListAdHost
import com.cla.clip.master.ui.widget.clip.ClipResultList
import com.cla.clip.master.ui.widget.clip.rememberClipListAdPresentation
import com.cla.clip.master.ui.widget.ad.FixedListAdLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 剪贴搜索页。
 *
 * 页面负责搜索输入、筛选条件选择、Result/History 模式切换和结果展示；
 * 查询组合、分页和剪贴操作都交给 SearchViewModel，让 UI 层只编排页面内交互状态。
 * 固定广告宿主跨 History/Result 保持组合，查询变化只影响业务结果与持续呈现许可。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchPage(
    /** 搜索业务状态与查询入口，由导航目的地作用域提供。 */
    viewModel: SearchViewModel = hiltViewModel(),
    /** 普通和折叠搜索共用页面，但保存态与查询范围隔离。 */
    scope: SearchScope = SearchScope.VisibleOnly,
    /** 详情跳转由宿主管理，页面不持有 NavController。 */
    onNavigate: (Route) -> Unit,
    /** 顶部显式离页入口，系统返回继续由现有处理链消费。 */
    onBack: () -> Unit,
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

    /** 结果卡片和菜单共用稳定加载边界，仅可见组合发起读取。 */
    val iconLoader = rememberAppIconLoader()
    /** 搜索筛选状态，包含关键词、时间范围和来源 App 集合。 */
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    /** 当前可选来源列表供下拉菜单使用，生命周期收集方式保持不变。 */
    val sourceApps by viewModel.sourceApps.collectAsStateWithLifecycle()
    /** 来源包名到展示名的映射，统一复用来源 App 显示名兜底规则。 */
    val sourceAppDisplayNames = sourceApps.associate { sourceApp ->
        sourceApp.packageName to sourceApp.appName.toSourceAppDisplayName()
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
    val quickAction by viewModel.quickAction.collectAsStateWithLifecycle(initialValue = ClipItemQuickAction.None)
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
    /** 两个菜单互斥，只保存瞬时展开项；搜索范围或主题重建时自动关闭。 */
    var expandedMenu by remember(saveableScopeKey, darkTheme) { mutableStateOf(SearchFilterMenu.None) }
    /** 浮层被动焦点恢复保护；真实触摸、键盘导航、读屏点击与编辑均可解除，按范围隔离。 */
    val searchInputFocusGuard = remember(saveableScopeKey) { SearchInputFocusGuard() }
    /** 页面失去前台时关闭菜单，避免后台恢复旧浮层。 */
    val menuLifecycleOwner = LocalLifecycleOwner.current
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
    /** 固定内容容器底部安全距离，输入法打开时同时保护历史、广告和结果操作区域。 */
    val historyBottomPadding = maxOf(navigationBarBottomPadding, imeBottomPadding)
    /** 固定容器顶部避让，展开时跟随可见搜索头底边，收起时至少保留状态栏安全区。 */
    val contentTopPaddingDp = with(density) {
        max(statusBarTopPx, headerHeightPx + headerOffsetPx).toDp()
    }
    /** 当前选中数量；底部栏、批量弹窗和按钮可用性共用同一份派生值。 */
    val selectedCount = selectedIds.size
    /** 普通搜索结果是否正在展示多选态；折叠搜索第一版不接入批量删除和批量折叠。 */
    val clipSelectionMode = isVisibleSearch && selectionMode
    /** 批量操作按钮是否可用；0 选中或执行中都不能触发删除/折叠。 */
    val batchActionsEnabled = clipSelectionMode && selectedCount > 0 && !isBatchActionRunning

    // 普通/折叠作用域是稳定访问身份；广告请求与数据库并行，不以模式、搜索词或节流作为前置门。
    val adSession = rememberPageAdSession(visibilityScope)
    // 所有确认、筛选弹层与批量任务都暂停广告交互及布局变化。
    val adOverlayBlocked = deleteClip != null || showBatchDeleteDialog || expandedMenu != SearchFilterMenu.None ||
        showClearHistoryConfirm || isBatchActionRunning
    /** 结果模式才收集分页，历史模式仍不提前启动剪贴查询；广告宿主始终独立组合。 */
    val pagedClips = if (pageMode == SearchPageMode.Result) {
        viewModel.pagedClips.collectAsLazyPagingItems()
    } else {
        null
    }
    /** 共享列表与页面固定区从同一当前快照读取真实内容和可见敏感许可，不复制一份迟到状态。 */
    val adPresentation = rememberClipListAdPresentation(pagedClips, resultListState)

    // 生命周期监听只关闭浮层，不回滚已生效筛选，不启动任何额外数据任务。
    DisposableEffect(menuLifecycleOwner, saveableScopeKey, darkTheme) {
        /** 页面暂停即关闭浮层，解除观察器由组合销毁负责。 */
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) expandedMenu = SearchFilterMenu.None
        }
        menuLifecycleOwner.lifecycle.addObserver(observer)
        // 组合销毁只移除监听，菜单状态由页面实例销毁或重建自然释放。
        onDispose { menuLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 菜单开关只改变浮层状态；打开时取消吸附动画并保护搜索框焦点恢复。 */
    fun updateFilterMenu(menu: SearchFilterMenu) {
        if (menu != SearchFilterMenu.None) {
            searchInputFocusGuard.onMenuOpened()
            headerSnapJob?.cancel()
        }
        expandedMenu = menu
    }

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

    BackHandler(enabled = clipSelectionMode && expandedMenu == SearchFilterMenu.None) {
        clearSelection()
    }

    /**
     * 将搜索头吸附到稳定两态。
     *
     * 只在 Result 模式执行；History 模式顶部固定展开，不参与吸附。
     */
    fun settleHeader(velocityY: Float = 0f) {
        if (pageMode != SearchPageMode.Result || headerHeightPx <= 0f || expandedMenu != SearchFilterMenu.None) {
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
                // 菜单展开时固定锚点，不让结果滚动推动搜索头。
                if (expandedMenu != SearchFilterMenu.None) return Offset.Zero
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
                // 菜单列表惯性滚动不驱动搜索头吸附。
                if (expandedMenu != SearchFilterMenu.None) return Velocity.Zero
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
            .adInteractionGuard(adSession)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { keyEvent ->
                // 菜单关闭后真实键盘输入/导航可解除被动恢复保护，事件继续交给系统或输入框处理。
                if (expandedMenu == SearchFilterMenu.None && keyEvent.type == KeyEventType.KeyDown) {
                    searchInputFocusGuard.onUserInputIntent()
                }
                false
            }
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
        FixedListAdLayout(
            session = adSession,
            placement = if (isVisibleSearch) AdPlacement.SearchFeedNative else AdPlacement.FoldedSearchNative,
            contentState = adPresentation.contentState,
            eligible = adPresentation.eligible,
            blocked = clipSelectionMode || adOverlayBlocked,
            canReveal = !adOverlayBlocked && headerSnapJob?.isActive != true,
            modifier = Modifier.fillMaxSize().padding(
                top = contentTopPaddingDp,
                bottom = historyBottomPadding,
            ),
        ) { remainingContentModifier ->
            // 历史/结果都消费宿主分配的剩余高度，广告位始终独立于业务模式与空态分支。
            if (pageMode == SearchPageMode.History) {
                SearchHistoryPanel(
                    modifier = remainingContentModifier,
                    listState = historyListState,
                    histories = searchHistories,
                    query = filterState.query,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        // 搜索头与底部系统区域已由固定容器避让，历史标题继续贴合可见操作区底边。
                        top = 0.dp,
                        end = 12.dp,
                        bottom = 24.dp,
                    ),
                    // 恢复历史关键词后进入结果；这次业务切换沿用页面广告访问。
                    onHistoryClick = { history ->
                        viewModel.selectHistory(history.query)
                        showResultMode()
                    },
                    onDeleteHistory = viewModel::deleteHistory,
                    // 清空历史先展示现有确认弹窗，固定宿主保持组合并暂停交互。
                    onClearHistories = { showClearHistoryConfirm = true }
                )
            } else {
                // 结果区离开组合就暂停名称装配；菜单行自己控制图标加载。
                SourceAppNameLoadingEffect(viewModel.sourceLoadGate)

                ClipResultList(
                    modifier = remainingContentModifier,
                    iconLoader = iconLoader,
                    adConfig = ClipListAdConfig(
                        session = adSession,
                        placement = if (isVisibleSearch) AdPlacement.SearchFeedNative else AdPlacement.FoldedSearchNative,
                        suppressed = clipSelectionMode || adOverlayBlocked,
                        layoutBlocked = adOverlayBlocked || headerSnapJob?.isActive == true,
                        host = ClipListAdHost.External,
                    ),
                    listState = resultListState,
                    // 与上方同一 pageMode 分支收集的分页引用必定存在，不延迟复制内容状态。
                    pagedClips = checkNotNull(pagedClips),
                    emptyText = stringResource(scope.emptyTextRes),
                    highlightQuery = filterState.query,
                    // 广告与搜索头已在列表外，结果仅保留卡片间距和本页面多选栏空间。
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        top = 10.dp,
                        end = 10.dp,
                        bottom = if (clipSelectionMode) 96.dp else 12.dp,
                    ),
                    // 折叠搜索保留置顶操作能力；数据层会先排置顶数据，再在分组内按 foldedAt 倒序。
                    onPinToggle = { clip -> viewModel.updatePinStatus(clip, !clip.isPinned) },
                    // 删除仍由页面弹窗确认，不把业务动作放进固定广告宿主。
                    onDelete = { deleteClip = it },
                    onCopy = viewModel::copyToClipboard,
                    // 第二段滑动保持普通折叠/折叠取消语义，完成后由同源快照更新内容许可。
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
                    // 普通多选仅切换选择；其他状态继续使用页面导航进入详情。
                    onClick = { clip ->
                        if (clipSelectionMode) {
                            toggleSelection(clip)
                        } else {
                            onNavigate(DetailRoute(clip.id))
                        }
                    },
                    // 长按入口仍由页面判定普通搜索是否允许多选，广告不进入选择状态。
                    onLongClick = { clip -> enterSelection(clip) }
                )
            }
        }

        SearchCollapsibleHeader(
            iconLoader = iconLoader,
            query = filterState.query,
            filterState = filterState,
            selectedSourceAppNames = selectedSourceAppNames,
            sourceApps = sourceApps,
            expandedMenu = expandedMenu,
            offsetPx = if (pageMode == SearchPageMode.History) 0f else headerOffsetPx,
            searchFieldModifier = Modifier.focusRequester(searchFocusRequester).pointerInput(saveableScopeKey) {
                awaitEachGesture {
                    // 只观察真实输入框按下，不消费事件，不把 Popup 关闭后的焦点恢复当点击。
                    awaitFirstDown(requireUnconsumed = false)
                    searchInputFocusGuard.onUserInputIntent()
                    if (pageMode == SearchPageMode.Result) showHistoryMode()
                }
            }.semantics {
                // 读屏语义点击没有指针事件；显式请求输入焦点与键盘，符合用户主动搜索意图。
                onClick {
                    searchInputFocusGuard.onUserInputIntent()
                    showHistoryMode()
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                    true
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { size ->
                    /** 最新搜索头高度来自真实布局测量，Collapsed 恢复必须使用这次高度。 */
                    headerHeightPx = size.height.toFloat()
                },
            onQueryChange = { query ->
                // 真实编辑允许进入历史模式，菜单焦点恢复仍被独立保护。
                searchInputFocusGuard.onUserInputIntent()
                viewModel.updateQuery(query)
                showHistoryMode()
            },
            onFocusChange = { focused ->
                // 保留真实焦点信息，但被动恢复不能触发历史模式或输入法副作用。
                searchBarFocused = focused
                if (searchInputFocusGuard.shouldShowHistory(focused)) {
                    showHistoryMode()
                }
            },
            // 显式返回逐层退出局部交互；离页分支一次收键盘并导航，不切换历史/结果模式。
            onBack = {
                when (resolveSearchToolbarBackAction(expandedMenu != SearchFilterMenu.None, clipSelectionMode)) {
                    SearchToolbarBackAction.CloseMenu -> updateFilterMenu(SearchFilterMenu.None)
                    SearchToolbarBackAction.ExitSelection -> clearSelection()
                    SearchToolbarBackAction.NavigateBack -> {
                        searchBarFocused = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onBack()
                    }
                }
            },
            // 清空是明确编辑意图；保留时间和来源，输入已失焦时也能重新继续输入。
            onClear = {
                searchInputFocusGuard.onUserInputIntent()
                viewModel.updateQuery("")
                showHistoryMode()
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            },
            // IME 继续提交剪贴搜索及历史，不能采用过滤页仅收键盘的处理。
            onSubmit = {
                viewModel.submitCurrentQuery()
                showResultMode()
            },
            onMenuChange = ::updateFilterMenu,
            onTimeSelected = viewModel::updateTimeFilter,
            onToggleAllSources = viewModel::toggleAllSourceApps,
            onToggleSource = viewModel::toggleSourceApp,
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
