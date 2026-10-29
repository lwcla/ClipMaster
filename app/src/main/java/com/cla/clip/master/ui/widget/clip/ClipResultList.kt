package com.cla.clip.master.ui.widget.clip

import com.cla.clip.master.ui.widget.sourceapp.AppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.EmptyAppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.rememberAppIconEntry
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.cla.clip.base.general.R as BaseR
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ad.rememberListAdLayoutCoordinator
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.ClipMasterCardDefaults
import com.cla.clip.master.ui.widget.ClipMasterGestureCard
import com.cla.clip.master.ui.widget.PagingLoadingContent
import com.cla.clip.master.ui.widget.PagingErrorContent
import com.cla.clip.master.ui.widget.pagingAppendStateItem
import com.cla.clip.master.ui.widget.ad.FixedListAdLayout
import com.cla.clip.master.ui.widget.ad.ListAdContentState
import kotlinx.coroutines.launch

/**
 * 取消置顶会触发分页结果重新排序，LazyColumn 默认会按稳定 key 跟随被移动的 item。
 *
 * 这里记录用户点击时的视口 index/offset，并在 Paging 快照确认目标记录已完成置顶状态变化后恢复该视口，
 * 让列表停留在用户发起操作时看到的位置。
 */
private data class PendingPinScrollRestore(
    /** 点击操作发生时列表首个可见 item 的 index。 */
    val index: Int,

    /** 点击操作发生时列表首个可见 item 的滚动偏移。 */
    val offset: Int,

    /** 操作前分页快照的顺序和置顶状态签名，用于判断异步排序刷新是否已经到达 UI。 */
    val snapshotSignature: List<Pair<Long, Boolean>>,
)

/**
 * 剪贴卡片时间展示模式。
 *
 * 普通列表按原剪贴时间展示；折叠范围按折叠时间展示；回收站按删除时间展示，避免排序依据和可见时间不一致。
 */
enum class ClipCardTimeMode {
    /** 展示剪贴记录原始时间。 */
    ClipTime,

    /** 展示本次折叠发生时间，用于折叠列表和折叠搜索。 */
    FoldedTime,

    /** 展示进入回收站的删除时间。 */
    DeletedTime
}

/**
 * 剪贴结果竖向列表。
 *
 * 该组件同时服务普通列表页和搜索页：列表页传空 `highlight`，搜索页传当前关键词。
 * 这里统一维护单列 item、侧滑操作、复制入口和分页加载状态，避免两个页面在后续迭代中出现体验分叉。
 * 固定广告宿主独立于内容/加载/空态分支；搜索页可明确声明外层宿主，保持跨历史模式的素材访问。
 */
@Composable
internal fun ClipResultList(
    listState: LazyListState,
    pagedClips: LazyPagingItems<ClipShowEntity>,
    emptyText: String,
    onPinToggle: ((ClipShowEntity) -> Unit)?,
    onDelete: ((ClipShowEntity) -> Unit)?,
    onCopy: ((ClipShowEntity) -> Unit)?,
    onSwipePastAction: ((ClipShowEntity) -> Unit)?,
    onClick: (ClipShowEntity) -> Unit,
    onLongClick: (ClipShowEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    /** 本次查询与字段绑定的高亮配置，普通列表为空。 */
    highlight: ClipSearchHighlight? = null,
    /** 页面提供的继续/松手提示；为空时只保留普通菜单。 */
    swipePastTexts: ClipSwipePastTexts? = null,
    timeMode: ClipCardTimeMode = ClipCardTimeMode.ClipTime,
    selectedIds: Set<Long> = emptySet(),
    selectionMode: Boolean = false,
    onToggleSelection: (ClipShowEntity) -> Unit = {},
    quickAction: ClipItemQuickAction = ClipItemQuickAction.None,
    enableQuickAction: Boolean = false,
    // 可空配置让 Preview 和未接入页面保持原有纯内容行为。
    adConfig: ClipListAdConfig? = null,
    /** 页面提供的稳定加载边界，Preview 使用默认占位。 */
    iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 首页保留组合的非当前 Pager 页不能发起系统取图。 */
    iconsVisibleToUser: Boolean = true,
    /** 页面可重建查询前置条件；未提供时沿用 Paging 原生 retry。 */
    onRefreshRetry: (() -> Unit)? = null,
) {
    // 取消置顶的排序变化由数据库/Paging 异步返回，先保存待恢复视口，再在快照确认后执行恢复。
    var pendingPinScrollRestore by remember { mutableStateOf<PendingPinScrollRestore?>(null) }
    // 共享列表只允许一个右滑菜单保持展开；新 item 开始右滑时写入该 id，其他 item 会观察到并自动收回。
    var openedMenuClipId by rememberSaveable { mutableStateOf<Long?>(null) }

    // 可见卡片的纯业务高度仍用于布局锚点，广告不进入卡片测量范围。
    val contentHeights = remember { mutableMapOf<Any, Int>() }
    // 侧滑动画独立于菜单展开状态，回收站没有菜单也需要保护离场动画。
    var interactingClipIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 真实渲染索引和内容三态使用同源快照，不把 Paging 占位符视作内容，也不检查正文。
    val adPresentation = rememberClipListAdPresentation(pagedClips)
    // 列表直接消费共享接线的渲染索引，保持稳定 key 和原 Paging 访问位置。
    val renderEntries = adPresentation.renderEntries
    // 侧滑、离场和置顶纠偏期间固定区不改变列表可用高度；页面动画保护继续透传。
    val adLayoutBlocked = adConfig?.layoutBlocked == true || openedMenuClipId != null ||
        interactingClipIds.isNotEmpty() || pendingPinScrollRestore != null
    if (adConfig != null) {
        rememberListAdLayoutCoordinator(
            session = adConfig.session,
            listState = listState,
            contentKeys = renderEntries.map { it.clipId },
            blocked = adLayoutBlocked,
            pinRestorePending = pendingPinScrollRestore != null,
            contentHeights = contentHeights,
        )
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            // 进入多选时清空父层菜单归属，避免底部批量栏出现后列表仍认为某个 item 处于展开状态。
            openedMenuClipId = null
        }
    }

    LaunchedEffect(pagedClips, listState) {
        snapshotFlow {
            val pending = pendingPinScrollRestore
            val currentSignature = pagedClips.itemSnapshotList.items.map { it.id to it.isPinned }
            pending?.takeIf {
                currentSignature.isNotEmpty() && currentSignature != it.snapshotSignature
            }
        }.collect { pending ->
            if (pending != null && pagedClips.itemCount > 0) {
                val targetIndex = pending.index.coerceIn(0, pagedClips.itemCount - 1)
                // 数据重排完成后再恢复视口，并覆盖下一次 remeasure 的 key 锚点策略，抵消 LazyColumn 自动跟随被移动 item 的默认行为。
                listState.requestScrollToItem(targetIndex, pending.offset)
                listState.scrollToItem(targetIndex, pending.offset)
                pendingPinScrollRestore = null
            }
        }
    }

    // 内容 slot 接收固定区扣除广告后的剩余空间，所有状态都使用该 Modifier 而不覆盖父层测量边界。
    val listContent: @Composable (Modifier) -> Unit = { contentModifier ->
        when (adPresentation.contentState) {
            ListAdContentState.Empty -> {
                if (pagedClips.loadState.refresh is LoadState.Error) {
                    // 无真实旧记录时仅显示既有重试状态，错误文字不能许可广告呈现。
                    PagingErrorContent(
                        text = stringResource(BaseR.string.base_general_data_load_failed_retry),
                        // 搜索页可重启前置名称查询，其他调用方仍由 Paging 直接重试。
                        onRetry = { (onRefreshRetry ?: pagedClips::retry).invoke() },
                        modifier = contentModifier,
                    )
                } else {
                    EmptyScreen(text = emptyText, modifier = contentModifier)
                }
            }

            ListAdContentState.Loading -> {
                // Paging 重新收集时可能短暂出现空快照；此时不组合绑定 listState 的空 LazyColumn，避免把保留的滚动位置钳回顶部。
                PagingLoadingContent(modifier = contentModifier)
            }

            ListAdContentState.Rendered -> {
                // append 错误文案复用基础资源；保留真实记录时 refresh/append 错误不收起整个列表。
                val retryText = stringResource(com.cla.clip.base.general.R.string.base_general_data_load_failed_retry)
                LazyColumn(
                    state = listState,
                    // 页面外壳统一观察按压；内层不重复写同一指针状态，避免子项释放提前解除根手势保护。
                    modifier = contentModifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (renderEntries.isNotEmpty()) {
                        items(
                            count = renderEntries.size,
                            // 当前快照里即使短暂出现重复记录，也只允许首个 clip.id 进入组合树，直接拦住 LazyColumn 重复 key 崩溃。
                            key = { index -> renderEntries[index].clipId },
                            contentType = { "ClipCard" }
                        ) { index ->
                            /** 当前要渲染的唯一条目；内部保留原 Paging 索引，避免破坏预取和回调定位。 */
                            val renderEntry = renderEntries[index]
                            /** 渲染可见唯一条目时访问真实源索引，保留 Paging 追加和预取提示；去重快照本身仍不触发加载。 */
                            val clip = pagedClips[renderEntry.sourceIndex]
                            if (clip != null) {
                                // 纯测量包装复用原卡片高度账本，删除尾槽能力后仍保持业务 Lazy 索引。
                                MeasuredClipContent(
                                    contentKey = clip.id,
                                    contentHeights = contentHeights,
                                ) {
                                    ClipCard(
                                        clip = clip,
                                        iconLoader = iconLoader,
                                        iconsVisibleToUser = iconsVisibleToUser,
                                        // 侧滑/离场开始与结束只更新当前交互集合，让固定广告延后改变业务视口高度。
                                        onInteractionChanged = { active ->
                                            interactingClipIds = if (active) interactingClipIds + clip.id else interactingClipIds - clip.id
                                        },
                                        highlight = highlight,
                                        onPinToggle = onPinToggle,
                                        onDelete = onDelete,
                                        onCopy = onCopy,
                                        onSwipePastAction = onSwipePastAction,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                        selected = clip.id in selectedIds,
                                        selectionMode = selectionMode,
                                        onToggleSelection = onToggleSelection,
                                        timeMode = timeMode,
                                        quickAction = quickAction,
                                        enableQuickAction = enableQuickAction,
                                        openedMenuClipId = openedMenuClipId,
                                        // 菜单归属继续使用业务 id，广告不参与共享互斥关系。
                                        onMenuActive = { clipId ->
                                            if (openedMenuClipId != clipId) {
                                                openedMenuClipId = clipId
                                            }
                                        },
                                        // 只清理仍属于该卡片的菜单状态，避免旧回调关闭新的可见菜单。
                                        onMenuInactive = { clipId ->
                                            if (openedMenuClipId == clipId) {
                                                openedMenuClipId = null
                                            }
                                        },
                                        swipePastTexts = swipePastTexts,
                                        // 取消置顶前同步保留业务视口，固定广告协调器在该待恢复期间让出优先权。
                                        onKeepCurrentScrollPosition = {
                                            pendingPinScrollRestore = PendingPinScrollRestore(
                                                index = listState.firstVisibleItemIndex,
                                                offset = listState.firstVisibleItemScrollOffset,
                                                snapshotSignature = pagedClips.itemSnapshotList.items.map { it.id to it.isPinned }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    pagingAppendStateItem(
                        loadState = pagedClips.loadState.append,
                        retryText = retryText,
                        onRetry = {
                            // append 加载失败时直接使用 Paging 的 retry，避免页面自己维护重试状态。
                            pagedClips.retry()
                        }
                    )
                }
            }
        }
    }

    if (adConfig != null && adConfig.host == ClipListAdHost.Fixed) {
        FixedListAdLayout(
            session = adConfig.session,
            placement = adConfig.placement,
            contentState = adPresentation.contentState,
            blocked = adConfig.suppressed || selectionMode,
            canReveal = !adLayoutBlocked,
            modifier = modifier,
            content = listContent,
        )
    } else {
        // 搜索由外层固定宿主提供剩余空间；Preview/未接入页面保持原纯内容入口。
        listContent(modifier)
    }
}

/**
 * 显示单个剪贴板内容的卡片。
 *
 * 卡片本身只处理展示和用户点击回调，不直接访问 ViewModel 或 Repository。
 *
 * 普通列表和普通搜索可以按设置开启斜向快捷动作区：左下三角执行页面传入的动作，
 * 其余区域进入详情；折叠列表、折叠搜索和回收站保持整卡点击语义。
 * 快捷动作区背景只作为底层视觉提示，内容始终按完整卡片宽度铺满。
 * 无有效来源主色时，边框、快捷区和角标统一使用主题 outline；仅来源色边框保留 30% 不透明度。
 * 第二段提示与松手共用纯规则，使用实测宽度的 80% 与菜单后保护距离，回拉或取消不提交。
 *
 * 因此普通列表页和搜索页可以在各自页面层决定置顶、删除、复制和详情跳转行为。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClipCard(
    clip: ClipShowEntity,
    onPinToggle: ((ClipShowEntity) -> Unit)?,
    onDelete: ((ClipShowEntity) -> Unit)?,
    onCopy: ((ClipShowEntity) -> Unit)?,
    onSwipePastAction: ((ClipShowEntity) -> Unit)?,
    onClick: (ClipShowEntity) -> Unit,
    onLongClick: (ClipShowEntity) -> Unit,
    onKeepCurrentScrollPosition: () -> Unit,
    modifier: Modifier = Modifier,
    /** 本次查询与字段绑定的高亮配置，普通列表为空。 */
    highlight: ClipSearchHighlight? = null,
    /** 页面提供的动作提示对；组件不解析文案来决定业务类型。 */
    swipePastTexts: ClipSwipePastTexts? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onToggleSelection: (ClipShowEntity) -> Unit = {},
    timeMode: ClipCardTimeMode = ClipCardTimeMode.ClipTime,
    quickAction: ClipItemQuickAction = ClipItemQuickAction.None,
    enableQuickAction: Boolean = false,
    openedMenuClipId: Long? = null,
    onMenuActive: (Long) -> Unit = {},
    onMenuInactive: (Long) -> Unit = {},
    // 侧滑与离场动画通知列表暂停广告布局变化，不包含业务动作副作用。
    onInteractionChanged: (Boolean) -> Unit = {},
    /** 与菜单/安装入口共享 Manager，但组件只依赖加载契约。 */
    iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 同时受目的地 STARTED 与首页实际可见状态约束。 */
    iconsVisibleToUser: Boolean = true,
) {
    /** 当前组合独占加载状态，图标与边框颜色使用同一结果。 */
    val appIconEntry = rememberAppIconEntry(clip.sourceAppPackage, iconLoader, iconsVisibleToUser)
    /** 来源主色沿用原有无效值约定；缺失或 -1 返回 null，不能通过颜色相等判断是否走默认色。 */
    val sourceAppColor = appIconEntry?.primaryColor
        ?.takeIf { it != -1 }?.let { Color(it) }
    /** 默认色复用公共卡片入口，随当前亮暗或动态主题变化，不额外降低不透明度。 */
    val defaultCardColor = ClipMasterCardDefaults.borderColor()
    /** 快捷区和置顶角标的共同基础色；冷缓存先用 outline，来源主色到达后与图标一起更新。 */
    val appColor = sourceAppColor ?: defaultCardColor
    /** 来源色边框保持原来的 30%，默认边框直接使用 outline，侧滑分隔线沿用同一结果。 */
    val borderColor = sourceAppColor?.copy(alpha = 0.3f) ?: defaultCardColor
    /** 快捷区常驻底色保留 5% 同源色，只作轻提示，避免压住整卡正文。 */
    val quickActionBackgroundColor = appColor.copy(alpha = 0.05f)
    /** 按压时在常驻底色上再叠加 10% 同源色，保留现有绘制顺序与反馈强度。 */
    val quickActionPressedColor = appColor.copy(alpha = 0.10f)
    val detailPressedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val density = LocalDensity.current
    val actionWidth = 48.dp
    // 分割线只承担视觉分区，不作为主要点击边界；高度按图标尺寸增加 10dp，并在按钮区域内竖向居中。
    val actionIconSize = 24.dp
    val dividerWidth = 0.5.dp
    val dividerHeight = actionIconSize + 10.dp
    val showCopyAction = !selectionMode && onCopy != null
    val showPinAction = !selectionMode && onPinToggle != null
    val showDeleteAction = !selectionMode && onDelete != null
    val actionCount = listOf(showCopyAction, showPinAction, showDeleteAction).count { it }
    val showActionMenu = actionCount > 0
    val actionAreaWidth = if (actionCount > 0) {
        actionWidth * actionCount + dividerWidth * (actionCount - 1)
    } else {
        0.dp
    }
    val maxOffsetPx = with(density) { actionAreaWidth.toPx() }
    val swipePastExitAnimationMs = 220
    val swipeSettleAnimationMs = 280
    val scope = rememberCoroutineScope()
    // 侧滑偏移按 clip.id 保存，避免 LazyColumn 复用 item 时把上一条记录的展开状态带给其他记录。
    var offsetPx by rememberSaveable(clip.id) { mutableStateOf(0f) }
    // 松手吸附和继续滑动离场都通过动画改变偏移；动画期间禁止继续拖动，避免状态和手势互相抢占。
    var isSwipeOffsetAnimating by rememberSaveable(clip.id) { mutableStateOf(false) }
    /** 实际布局宽度，未测量为零；不使用可误触发动作的兜底尺寸。 */
    var itemWidthPx by remember(clip.id) { mutableStateOf(0f) }
    /** 拖动和离场终点均为完整实测宽度，触发距离由独立规则计算。 */
    val swipePastDragMaxPx = itemWidthPx.coerceAtLeast(0f)
    /** 80% 基准并保留菜单后保护距离；无有效测量时 null 禁止第二段。 */
    val swipePastTriggerPx = calculateClipSwipePastThreshold(
        itemWidthPx = itemWidthPx,
        menuWidthPx = maxOffsetPx,
        minimumExtraDistancePx = with(density) { 48.dp.toPx() },
    )
    val offsetDp = with(density) { offsetPx.toDp() }
    val pinDescription = stringResource(
        if (clip.isPinned) {
            com.cla.clip.base.general.R.string.base_general_unpinned
        } else {
            com.cla.clip.base.general.R.string.base_general_pinned
        }
    )
    val deleteDescription = stringResource(com.cla.clip.base.general.R.string.base_general_delete)
    val copyDescription = stringResource(com.cla.clip.base.general.R.string.base_general_copy)
    val detailDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clip_detail)
    val canRunQuickAction = !selectionMode &&
        enableQuickAction &&
        quickAction != ClipItemQuickAction.None &&
        when (quickAction) {
            ClipItemQuickAction.Copy -> onCopy != null
            ClipItemQuickAction.Pin -> onPinToggle != null
            ClipItemQuickAction.Delete -> onDelete != null
            ClipItemQuickAction.Fold -> onSwipePastAction != null
            ClipItemQuickAction.None -> false
        }
    // pointerInput 内部的手势协程不一定会随普通重组重启；业务回调和当前 item 快照使用最新引用，
    // 只解决手势协程捕获旧闭包的问题，不改变页面级转场和点击命中策略。
    val currentClip by rememberUpdatedState(clip)
    val currentQuickAction by rememberUpdatedState(quickAction)
    val currentOnPinToggle by rememberUpdatedState(onPinToggle)
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnCopy by rememberUpdatedState(onCopy)
    val currentOnSwipePastAction by rememberUpdatedState(onSwipePastAction)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnToggleSelection by rememberUpdatedState(onToggleSelection)
    val currentOnKeepCurrentScrollPosition by rememberUpdatedState(onKeepCurrentScrollPosition)
    val currentOnMenuActive by rememberUpdatedState(onMenuActive)
    val currentOnMenuInactive by rememberUpdatedState(onMenuInactive)
    // 按压态只用于绘制轻量反馈；任何拖动、长按、取消或 item 复用都会清空，避免三角反馈卡亮。
    var pressedZone by remember(clip.id) { mutableStateOf<ClipCardPressedZone?>(null) }
    /** 当前是否有未结束的拖动；只用于 key 重启清理，不保存到页面恢复状态或参与手势 key。 */
    var isDragging by remember(clip.id) { mutableStateOf(false) }
    // 外层 Card、侧滑内容和边框共用同一个圆角，保证阴影、水波纹和裁剪视觉一致。
    val cardShape = ClipMasterCardDefaults.shape
    /** 多选态点击语义文案，TalkBack 会据此说明当前点击是选中或取消选中，而不是查看详情。 */
    val selectionClickDescription = stringResource(
        if (selected) {
            com.cla.clip.base.general.R.string.base_general_unselect
        } else {
            com.cla.clip.base.general.R.string.base_general_select
        }
    )
    /** 同时具备文案、回调及实测阈值才允许第二段；多选态始终关闭。 */
    val effectiveSwipePastTexts = if (selectionMode || onSwipePastAction == null || swipePastTriggerPx == null) null else swipePastTexts
    /** 展示阶段只从当前偏移派生；不能用作 pointerInput key，以免跨阈值时取消拖动。 */
    val swipePastState = resolveClipSwipePastState(
        offsetPx = offsetPx,
        menuWidthPx = maxOffsetPx,
        triggerPx = if (effectiveSwipePastTexts != null) swipePastTriggerPx else null,
    )

    // 与下方 pointerInput 的配置 key 保持一致：key 重启是协程取消，不会经过指针 cancel 回调。
    // 只清理正在拖动的状态，不重置已吸附菜单或正在执行的离场动画，也不执行任何业务动作。
    DisposableEffect(clip.id, maxOffsetPx, swipePastDragMaxPx, swipePastTriggerPx, effectiveSwipePastTexts, canRunQuickAction, selectionMode) {
        onDispose {
            if (isDragging && !isSwipeOffsetAnimating) {
                offsetPx = 0f
                currentOnMenuInactive(clip.id)
            }
            isDragging = false
            pressedZone = null
        }
    }

    /** 执行普通列表/普通搜索快捷动作区动作，具体业务仍由页面层回调决定。 */
    fun runQuickAction() {
        val latestClip = currentClip
        when (currentQuickAction) {
            ClipItemQuickAction.Copy -> currentOnCopy?.invoke(latestClip)
            ClipItemQuickAction.Pin -> {
                if (latestClip.isPinned) {
                    currentOnKeepCurrentScrollPosition()
                }
                currentOnPinToggle?.invoke(latestClip)
            }

            ClipItemQuickAction.Delete -> {
                // 删除动作只触发页面层现有删除选择弹窗，这是防止误触后静默删除的安全边界。
                currentOnDelete?.invoke(latestClip)
            }

            ClipItemQuickAction.Fold -> currentOnSwipePastAction?.invoke(latestClip)
            ClipItemQuickAction.None -> currentOnClick(latestClip)
        }
    }
    /**
     * 播放侧滑偏移动画。
     *
     * 未达到折叠阈值时用较长的吸附动画回到菜单或收起状态，避免从大幅拖动位置瞬间跳回；
     * 真正触发折叠/取消折叠时则滑到 item 外侧，动画结束后再让页面层更新数据库。
     */
    fun animateOffsetTo(
        targetOffsetPx: Float,
        durationMillis: Int,
        keepAnimatingAfterEnd: Boolean = false,
        onFinished: (() -> Unit)? = null,
    ) {
        if (isSwipeOffsetAnimating) return
        isSwipeOffsetAnimating = true
        scope.launch {
            val animator = Animatable(offsetPx)
            animator.animateTo(
                targetValue = targetOffsetPx,
                animationSpec = tween(durationMillis = durationMillis)
            ) {
                offsetPx = value
            }
            offsetPx = targetOffsetPx
            if (!keepAnimatingAfterEnd) {
                isSwipeOffsetAnimating = false
            }
            onFinished?.invoke()
        }
    }

    /**
     * 根据吸附后的最终位置同步父层的单展开菜单归属。
     *
     * 只有真正停在菜单展开位置时才登记当前 item；回到 0、触发第二段动作或被其他 item 抢占时都释放当前归属。
     */
    fun syncMenuOwnerAfterSettle(targetOffsetPx: Float) {
        if (showActionMenu && targetOffsetPx > 0f) {
            currentOnMenuActive(clip.id)
        } else {
            currentOnMenuInactive(clip.id)
        }
    }

    // 最新回调避免 Lazy 复用或父层重组后仍写入旧交互集合。
    val currentOnInteractionChanged by rememberUpdatedState(onInteractionChanged)
    // 菜单完全收起且动画停止后才允许广告改高度；离场期间一直保护到 item 退出组合。
    SideEffect { currentOnInteractionChanged(isSwipeOffsetAnimating || offsetPx != 0f) }
    DisposableEffect(clip.id) { onDispose { currentOnInteractionChanged(false) } }

    val shouldCloseForOtherMenu = showActionMenu &&
        openedMenuClipId != null &&
        openedMenuClipId != clip.id &&
        offsetPx > 0f

    LaunchedEffect(shouldCloseForOtherMenu, isSwipeOffsetAnimating) {
        if (shouldCloseForOtherMenu && !isSwipeOffsetAnimating) {
            // 父层已经把菜单归属切到其他 item，本 item 如果还展开着，需要自动收回，保证列表里最多只有一个菜单可见。
            animateOffsetTo(
                targetOffsetPx = 0f,
                durationMillis = swipeSettleAnimationMs,
                onFinished = { currentOnMenuInactive(clip.id) }
            )
        }
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode && offsetPx != 0f && !isSwipeOffsetAnimating) {
            // 进入多选时主动收回已经展开的右滑菜单，避免菜单按钮和底部批量操作同时出现在屏幕上。
            animateOffsetTo(
                targetOffsetPx = 0f,
                durationMillis = swipeSettleAnimationMs,
                onFinished = { currentOnMenuInactive(clip.id) }
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                // 实测宽度驱动 80% 与菜单保护规则；首次测量之前禁止提交，离场后才刷新数据库。
                itemWidthPx = size.width.toFloat()
            }
    ) {
        // 第二段使用完整暴露区展示提示；菜单移出组合，避免文字叠加和隐藏按钮仍可点击/访问。
        if (showActionMenu && swipePastState == ClipSwipePastState.Hidden) {
            // 外层 item 需要保留未裁剪空间给公共卡片阴影；侧滑菜单自身仍按卡片圆角裁剪，避免右滑露出方角背景。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.CenterStart)
                    .clip(cardShape)
                    .clipToBounds(),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .width(actionAreaWidth)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var hasPreviousAction = false

                    if (showCopyAction) {
                        SwipeActionButton(
                            modifier = Modifier
                                .width(actionWidth)
                                .fillMaxHeight(),
                            iconTint = MaterialTheme.colorScheme.primary,
                            iconContentDescription = copyDescription,
                            iconSize = actionIconSize,
                            painterRes = R.drawable.host_icon_copy,
                            onClick = {
                                offsetPx = 0f
                                currentOnMenuInactive(clip.id)
                                currentOnCopy?.invoke(currentClip)
                            }
                        )
                        hasPreviousAction = true
                    }

                    if (showPinAction) {
                        if (hasPreviousAction) {
                            Box(
                                modifier = Modifier
                                    .width(dividerWidth)
                                    .height(dividerHeight)
                                    .align(Alignment.CenterVertically)
                                    .background(borderColor)
                            )
                        }

                        SwipeActionButton(
                            modifier = Modifier
                                .width(actionWidth)
                                .fillMaxHeight(),
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            iconContentDescription = pinDescription,
                            iconSize = actionIconSize,
                            painterRes = if (clip.isPinned) {
                                R.drawable.host_icon_unpinned
                            } else {
                                R.drawable.host_icon_to_pinned
                            },
                            onClick = {
                                val latestClip = currentClip
                                if (latestClip.isPinned) {
                                    currentOnKeepCurrentScrollPosition()
                                }
                                offsetPx = 0f
                                currentOnMenuInactive(clip.id)
                                currentOnPinToggle?.invoke(latestClip)
                            }
                        )
                        hasPreviousAction = true
                    }

                    if (showDeleteAction) {
                        if (hasPreviousAction) {
                            Box(
                                modifier = Modifier
                                    .width(dividerWidth)
                                    .height(dividerHeight)
                                    .align(Alignment.CenterVertically)
                                    .background(borderColor)
                            )
                        }

                        SwipeActionButton(
                            modifier = Modifier
                                .width(actionWidth)
                                .fillMaxHeight(),
                            iconTint = MaterialTheme.colorScheme.error,
                            iconContentDescription = deleteDescription,
                            iconSize = actionIconSize,
                            painterRes = BaseR.drawable.base_general_ic_delete,
                            onClick = {
                                offsetPx = 0f
                                currentOnMenuInactive(clip.id)
                                currentOnDelete?.invoke(currentClip)
                            }
                        )
                    }
                }
            }
        }

        if (effectiveSwipePastTexts != null && swipePastState != ClipSwipePastState.Hidden) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.CenterStart)
                    .clip(cardShape)
                    .clipToBounds(),
                contentAlignment = Alignment.CenterStart
            ) {
                ClipSwipePastHint(
                    state = swipePastState,
                    texts = effectiveSwipePastTexts,
                    modifier = Modifier.width(offsetDp.coerceAtLeast(0.dp)).fillMaxHeight(),
                )
            }
        }

        ClipMasterGestureCard(
            shape = cardShape,
            borderColor = borderColor,
            contentPadding = ClipMasterCardDefaults.ZeroContentPadding,
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = offsetDp)
                .semantics {
                    if (selectionMode) {
                        // 多选态点击语义必须与实际行为一致：点击只切换选中，不进入详情。
                        this.selected = selected
                        onClick(label = selectionClickDescription) {
                            currentOnToggleSelection(currentClip)
                            true
                        }
                    } else {
                        // 自定义几何热区不自带可访问性语义；默认无障碍点击先保持进入详情，快捷动作留给后续自定义语义动作补充。
                        onClick(label = detailDescription) {
                            currentOnClick(currentClip)
                            true
                        }
                    }
                    onLongClick {
                        currentOnLongClick(currentClip)
                        true
                    }
                }
                .pointerInput(clip.id, maxOffsetPx, swipePastDragMaxPx, swipePastTriggerPx, effectiveSwipePastTexts, canRunQuickAction, selectionMode) {
                    detectClipCardGestures(
                        isMenuOpened = { offsetPx > 0f },
                        isAnimating = { isSwipeOffsetAnimating },
                        isQuickActionEnabled = { canRunQuickAction },
                        isSwipeEnabled = { !selectionMode },
                        onPressZoneChanged = { pressedZone = it },
                        onTap = { _, isQuickActionTap ->
                            if (selectionMode) {
                                currentOnToggleSelection(currentClip)
                            } else if (isQuickActionTap) {
                                runQuickAction()
                            } else {
                                currentOnClick(currentClip)
                            }
                        },
                        onLongPress = { currentOnLongClick(currentClip) },
                        onDrag = { dragAmount ->
                            if (!isSwipeOffsetAnimating) {
                                // 标记真实拖动，以便布局或配置重启协程时收回未提交偏移。
                                isDragging = true
                                // 当前横向增量与已有偏移叠加，动画期间不接受新的拖动。
                                val nextOffset = offsetPx + dragAmount
                                if (showActionMenu && nextOffset > 0f) {
                                    // 当前 item 一旦开始展开菜单，就抢占单展开归属，让上一个已展开 item 自动收回。
                                    currentOnMenuActive(clip.id)
                                }
                                // 只允许向右展开、向左收回；存在继续滑动动作时允许进入第二段提示区，但仍限制最大距离避免 item 被拖离过远。
                                val dragMaxOffset = if (effectiveSwipePastTexts == null) maxOffsetPx else swipePastDragMaxPx
                                offsetPx = nextOffset.coerceIn(0f, dragMaxOffset)
                            }
                        },
                        onDragEnd = {
                            // 正常松手结束当前拖动，后续阶段交给动画锁保护。
                            isDragging = false
                            // 松手读取当前偏移并复用展示规则，不捕获上一帧阶段；缺回调或回拉不提交。
                            val shouldRunSwipePastAction = currentOnSwipePastAction != null &&
                                resolveClipSwipePastState(offsetPx, maxOffsetPx, if (effectiveSwipePastTexts != null) swipePastTriggerPx else null) == ClipSwipePastState.Ready
                            if (shouldRunSwipePastAction && !isSwipeOffsetAnimating) {
                                // 达标后仍滑出整卡，只有动画结束才更新数据，且动画锁防止重复提交。
                                val targetOffsetPx = swipePastDragMaxPx
                                // 先让卡片完整滑出当前 item，动画结束后再更新折叠状态，避免 Paging 立刻刷新造成半途消失。
                                animateOffsetTo(
                                    targetOffsetPx = targetOffsetPx,
                                    durationMillis = swipePastExitAnimationMs,
                                    keepAnimatingAfterEnd = true,
                                    onFinished = {
                                        currentOnMenuInactive(clip.id)
                                        currentOnSwipePastAction?.invoke(currentClip)
                                    }
                                )
                            } else {
                                // 松手时按操作区一半作为吸附阈值，短距离误滑会自动回收，明显右滑会保持展开。
                                val targetOffsetPx = if (showActionMenu && offsetPx > maxOffsetPx / 2f) maxOffsetPx else 0f
                                animateOffsetTo(
                                    targetOffsetPx = targetOffsetPx,
                                    durationMillis = swipeSettleAnimationMs,
                                    onFinished = { syncMenuOwnerAfterSettle(targetOffsetPx) }
                                )
                            }
                        },
                        onDragCancel = {
                            // 指针取消走既有吸附；配置取消由与 pointerInput 同 key 的清理补齐。
                            isDragging = false
                            // 手势被系统或父级中断时同样做吸附，避免停在半展开的不可预期位置。
                            val targetOffsetPx = if (showActionMenu && !isSwipeOffsetAnimating && offsetPx > maxOffsetPx / 2f) {
                                maxOffsetPx
                            } else {
                                0f
                            }
                            animateOffsetTo(
                                targetOffsetPx = targetOffsetPx,
                                durationMillis = swipeSettleAnimationMs,
                                onFinished = { syncMenuOwnerAfterSettle(targetOffsetPx) }
                            )
                        }
                    )
            },
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                if (canRunQuickAction || pressedZone != null) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        if (canRunQuickAction) {
                            // 底色和命中判断共用同一组三角规则，避免视觉区域与实际点击区域错位。
                            drawPath(
                                path = quickActionZonePath(size.width, size.height),
                                color = quickActionBackgroundColor
                            )
                        }

                        when (pressedZone) {
                            ClipCardPressedZone.QuickAction -> {
                                drawPath(
                                    path = quickActionZonePath(size.width, size.height),
                                    color = quickActionPressedColor
                                )
                            }

                            ClipCardPressedZone.Detail -> {
                                val detailPath = if (canRunQuickAction) {
                                    detailZonePath(size.width, size.height)
                                } else {
                                    fullCardPath(size.width, size.height)
                                }
                                drawPath(
                                    path = detailPath,
                                    color = detailPressedColor
                                )
                            }

                            null -> Unit
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        // 内容层始终使用整张卡片宽度，避免快捷动作区改变文本排版和关键词高亮结果。
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ClipContent(clip, highlight)

                        Spacer(Modifier.height(8.dp))
                        SourceAppNameWithTime(clip, highlight, timeMode, appIconEntry?.icon)
                    }
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(24.dp)
                        )
                    }
                }

                if (clip.isPinned) {
                    Icon(
                        painterResource(R.drawable.host_icon_pinned),
                        contentDescription = null,
                        modifier = Modifier
                            // 置顶角标只做状态暗示，透明度保持很低，避免覆盖右上角剪贴文案时影响阅读。
                            .zIndex(2f)
                            .width(42.dp)
                            .align(Alignment.TopEnd)
                            .alpha(0.4f),
                        tint = appColor
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 150, heightDp = 300)
@Preview(name = "深色长名称缺图", widthDp = 180, heightDp = 300, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
/** 长名称与冷缓存占位的纯预览，不启动 Manager 或系统读取。 */
private fun ClipCardPreview() {
    /** 合成正文与历史名称，Preview 不启动系统查询或文件读取。 */
    val clip = ClipShowEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。用于预览ClipCard组件的显示效果。",
        timestamp = System.currentTimeMillis(),
        foldedAt = System.currentTimeMillis(),
        deletedAt = System.currentTimeMillis(),
        formattedTime = "刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚",
        sourceAppPackage = "com.example.preview",
        isPinned = false,
        isFolded = false,
        link = "https://www.wanandroid.com/",
        linkImgUrl = "https://img0.baidu.com/it/u=2280054277,2128244139&fm=253&fmt=auto&app=138&f=JPEG?w=973&h=304",
        linkTitle = "这是链接的标题这是链接的标题这是链接的标题",
        sourceAppName = "飞书长名称来源应用预览",
    )

    ClipCard(
        clip = clip,
        highlight = ClipSearchHighlight("示例 飞书"),
        onPinToggle = {},
        onDelete = {},
        onCopy = {},
        onSwipePastAction = {},
        onClick = {},
        onLongClick = {},
        swipePastTexts = ClipSwipePastTexts(
            continueText = stringResource(BaseR.string.base_general_continue_swipe_to_fold_clip),
            releaseText = stringResource(BaseR.string.base_general_release_to_fold_clip),
        ),
        onKeepCurrentScrollPosition = {}
    )
}
