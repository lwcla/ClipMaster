package com.cla.clip.master.ui.widget.clip

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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.ClipMasterCardDefaults
import com.cla.clip.master.ui.widget.ClipMasterGestureCard
import com.cla.clip.master.ui.widget.PagingLoadingContent
import com.cla.clip.master.ui.widget.pagingAppendStateItem
import kotlinx.coroutines.launch
import kotlin.math.max

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
 * 该组件同时服务普通列表页和搜索页：列表页传空 `highlightQuery`，搜索页传当前关键词。
 * 这里统一维护单列 item、侧滑操作、复制入口和分页加载状态，避免两个页面在后续迭代中出现体验分叉。
 */
@Composable
fun ClipResultList(
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
    highlightQuery: String? = null,
    swipePastActionText: String? = null,
    timeMode: ClipCardTimeMode = ClipCardTimeMode.ClipTime,
    selectedIds: Set<Long> = emptySet(),
    quickAction: ClipItemQuickAction = ClipItemQuickAction.None,
    enableQuickAction: Boolean = false,
) {
    // 取消置顶的排序变化由数据库/Paging 异步返回，先保存待恢复视口，再在快照确认后执行恢复。
    var pendingPinScrollRestore by remember { mutableStateOf<PendingPinScrollRestore?>(null) }
    // 共享列表只允许一个右滑菜单保持展开；新 item 开始右滑时写入该 id，其他 item 会观察到并自动收回。
    var openedMenuClipId by rememberSaveable { mutableStateOf<Long?>(null) }

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

    when {
        pagedClips.loadState.refresh is LoadState.NotLoading && pagedClips.itemCount == 0 -> {
            EmptyScreen(text = emptyText, modifier = modifier)
        }

        pagedClips.loadState.refresh is LoadState.Loading && pagedClips.itemCount == 0 -> {
            // Paging 重新收集时可能短暂出现空快照；此时不组合绑定 listState 的空 LazyColumn，避免把保留的滚动位置钳回顶部。
            PagingLoadingContent(modifier = modifier)
        }

        else -> {
            val retryText = stringResource(com.cla.clip.base.general.R.string.base_general_data_load_failed_retry)
            // 共享列表只基于当前已加载快照生成唯一渲染索引，避免 Paging 切代重排瞬间把同一 clip.id 交给 LazyColumn 两次。
            val renderEntries = remember(pagedClips.itemSnapshotList) {
                buildUniqueClipPagingRenderEntries(pagedClips.itemSnapshotList)
            }
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
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
                        /** 使用真实 Paging 索引读取条目；这里用 peek 避免组合阶段因为防御性去重反向触发额外加载。 */
                        val clip = pagedClips.peek(renderEntry.sourceIndex)
                        if (clip != null) {
                            ClipCard(
                                clip = clip,
                                highlightQuery = highlightQuery,
                                onPinToggle = onPinToggle,
                                onDelete = onDelete,
                                onCopy = onCopy,
                                onSwipePastAction = onSwipePastAction,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                selected = clip.id in selectedIds,
                                timeMode = timeMode,
                                quickAction = quickAction,
                                enableQuickAction = enableQuickAction,
                                openedMenuClipId = openedMenuClipId,
                                onMenuActive = { clipId ->
                                    if (openedMenuClipId != clipId) {
                                        openedMenuClipId = clipId
                                    }
                                },
                                onMenuInactive = { clipId ->
                                    if (openedMenuClipId == clipId) {
                                        openedMenuClipId = null
                                    }
                                },
                                swipePastActionText = swipePastActionText,
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

/**
 * 显示单个剪贴板内容的卡片。
 *
 * 卡片本身只处理展示和用户点击回调，不直接访问 ViewModel 或 Repository。
 *
 * 普通列表和普通搜索可以按设置开启斜向快捷动作区：左下三角执行页面传入的动作，
 * 其余区域进入详情；折叠列表、折叠搜索和回收站保持整卡点击语义。
 * 快捷动作区背景只作为底层视觉提示，内容始终按完整卡片宽度铺满。
 *
 * 因此普通列表页和搜索页可以在各自页面层决定置顶、删除、复制和详情跳转行为。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipCard(
    clip: ClipShowEntity,
    onPinToggle: ((ClipShowEntity) -> Unit)?,
    onDelete: ((ClipShowEntity) -> Unit)?,
    onCopy: ((ClipShowEntity) -> Unit)?,
    onSwipePastAction: ((ClipShowEntity) -> Unit)?,
    onClick: (ClipShowEntity) -> Unit,
    onLongClick: (ClipShowEntity) -> Unit,
    onKeepCurrentScrollPosition: () -> Unit,
    modifier: Modifier = Modifier,
    highlightQuery: String? = null,
    swipePastActionText: String? = null,
    selected: Boolean = false,
    timeMode: ClipCardTimeMode = ClipCardTimeMode.ClipTime,
    quickAction: ClipItemQuickAction = ClipItemQuickAction.None,
    enableQuickAction: Boolean = false,
    openedMenuClipId: Long? = null,
    onMenuActive: (Long) -> Unit = {},
    onMenuInactive: (Long) -> Unit = {},
) {
    val appColor = clip.appColor ?: MaterialTheme.colorScheme.outlineVariant
    val borderColor = appColor.copy(alpha = 0.3f)
    // 斜向快捷区底色只保留很轻的同源提示，避免压住铺满整 卡的剪贴内容。
    val quickActionBackgroundColor = appColor.copy(alpha = 0.05f)
    val quickActionPressedColor = appColor.copy(alpha = 0.10f)
    val detailPressedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val density = LocalDensity.current
    val actionWidth = 48.dp
    // 分割线只承担视觉分区，不作为主要点击边界；高度按图标尺寸增加 10dp，并在按钮区域内竖向居中。
    val actionIconSize = 24.dp
    val dividerWidth = 0.5.dp
    val dividerHeight = actionIconSize + 10.dp
    val showCopyAction = onCopy != null
    val showPinAction = onPinToggle != null
    val showDeleteAction = onDelete != null
    val actionCount = listOf(showCopyAction, showPinAction, showDeleteAction).count { it }
    val showActionMenu = actionCount > 0
    val actionAreaWidth = if (actionCount > 0) {
        actionWidth * actionCount + dividerWidth * (actionCount - 1)
    } else {
        0.dp
    }
    val maxOffsetPx = with(density) { actionAreaWidth.toPx() }
    val fallbackSwipePastOffsetPx = if (maxOffsetPx > 0f) {
        maxOffsetPx * 2f
    } else {
        // 回收站无菜单模式首次测量前没有操作区宽度，给继续右滑一个兜底距离，避免阈值为 0 导致误触发。
        with(density) { 160.dp.toPx() }
    }
    val swipePastExitAnimationMs = 220
    val swipeSettleAnimationMs = 280
    val scope = rememberCoroutineScope()
    // 侧滑偏移按 clip.id 保存，避免 LazyColumn 复用 item 时把上一条记录的展开状态带给其他记录。
    var offsetPx by rememberSaveable(clip.id) { mutableStateOf(0f) }
    // 松手吸附和继续滑动离场都通过动画改变偏移；动画期间禁止继续拖动，避免状态和手势互相抢占。
    var isSwipeOffsetAnimating by rememberSaveable(clip.id) { mutableStateOf(false) }
    var itemWidthPx by remember(clip.id) { mutableStateOf(0f) }
    val swipePastDragMaxPx = itemWidthPx.takeIf { it > 0f } ?: fallbackSwipePastOffsetPx
    // 继续右滑触发阈值按 item 宽度 85% 计算，让最后阶段仍跟随手指，同时降低触发折叠/取消折叠的拖动压力。
    val swipePastTriggerPx = max(maxOffsetPx, swipePastDragMaxPx * 0.85f)
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
    val canRunQuickAction = enableQuickAction &&
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
    val currentOnKeepCurrentScrollPosition by rememberUpdatedState(onKeepCurrentScrollPosition)
    val currentOnMenuActive by rememberUpdatedState(onMenuActive)
    val currentOnMenuInactive by rememberUpdatedState(onMenuInactive)
    // 按压态只用于绘制轻量反馈；任何拖动、长按、取消或 item 复用都会清空，避免三角反馈卡亮。
    var pressedZone by remember(clip.id) { mutableStateOf<ClipCardPressedZone?>(null) }
    // 外层 Card、侧滑内容和边框共用同一个圆角，保证阴影、水波纹和裁剪视觉一致。
    val cardShape = ClipMasterCardDefaults.shape

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                // 记录 item 实际宽度，第二段动作按 85% 宽度触发，并在触发后把卡片滑到屏幕外再刷新数据库。
                itemWidthPx = size.width.toFloat()
            }
    ) {
        if (showActionMenu) {
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

        if (swipePastActionText != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.CenterStart)
                    .clip(cardShape)
                    .clipToBounds(),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(start = actionAreaWidth),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val progress = ((offsetPx - maxOffsetPx) / (swipePastTriggerPx - maxOffsetPx))
                        .takeIf { it.isFinite() }
                        ?.coerceIn(0f, 1f)
                        ?: if (offsetPx >= swipePastTriggerPx) 1f else 0f
                    Text(
                        text = swipePastActionText,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .alpha(progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                    // 自定义几何热区不自带可访问性语义；默认无障碍点击先保持进入详情，快捷动作留给后续自定义语义动作补充。
                    onClick(label = detailDescription) {
                        currentOnClick(currentClip)
                        true
                    }
                    onLongClick {
                        currentOnLongClick(currentClip)
                        true
                    }
                }
                .pointerInput(clip.id, maxOffsetPx, swipePastDragMaxPx, swipePastTriggerPx, swipePastActionText, canRunQuickAction) {
                    detectClipCardGestures(
                        isMenuOpened = { offsetPx > 0f },
                        isAnimating = { isSwipeOffsetAnimating },
                        isQuickActionEnabled = { canRunQuickAction },
                        onPressZoneChanged = { pressedZone = it },
                        onTap = { _, isQuickActionTap ->
                            if (isQuickActionTap) {
                                runQuickAction()
                            } else {
                                currentOnClick(currentClip)
                            }
                        },
                        onLongPress = { currentOnLongClick(currentClip) },
                        onDrag = { dragAmount ->
                            if (!isSwipeOffsetAnimating) {
                                val nextOffset = offsetPx + dragAmount
                                if (showActionMenu && nextOffset > 0f) {
                                    // 当前 item 一旦开始展开菜单，就抢占单展开归属，让上一个已展开 item 自动收回。
                                    currentOnMenuActive(clip.id)
                                }
                                // 只允许向右展开、向左收回；存在继续滑动动作时允许进入第二段提示区，但仍限制最大距离避免 item 被拖离过远。
                                val dragMaxOffset = if (swipePastActionText == null) maxOffsetPx else swipePastDragMaxPx
                                offsetPx = nextOffset.coerceIn(0f, dragMaxOffset)
                            }
                        },
                        onDragEnd = {
                            // 第二段继续右滑只在松手且超过阈值时触发，避免用户只是查看菜单时误折叠、误取消折叠或误彻底删除。
                            val shouldRunSwipePastAction = onSwipePastAction != null && swipePastActionText != null && offsetPx >= swipePastTriggerPx
                            if (shouldRunSwipePastAction && !isSwipeOffsetAnimating) {
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
                        ClipContent(clip, highlightQuery)

                        Spacer(Modifier.height(8.dp))
                        SourceAppNameWithTime(clip, highlightQuery, timeMode)
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
@Composable
private fun ClipCardPreview() {
    val clip = ClipShowEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。用于预览ClipCard组件的显示效果。",
        timestamp = System.currentTimeMillis(),
        foldedAt = System.currentTimeMillis(),
        deletedAt = System.currentTimeMillis(),
        formattedTime = "刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚",
        appName = "飞书飞书飞书飞书",
        appIconPath = "https://img2.baidu.com/it/u=3546907450,5411894&fm=253&fmt=auto&app=120&f=JPEG?w=500&h=500",
        appIconHash = "preview_icon_hash",
        appColor = MaterialTheme.colorScheme.error,
        isPinned = false,
        isFolded = false,
        link = "https://www.wanandroid.com/",
        linkImgUrl = "https://img0.baidu.com/it/u=2280054277,2128244139&fm=253&fmt=auto&app=138&f=JPEG?w=973&h=304",
        linkTitle = "这是链接的标题这是链接的标题这是链接的标题"
    )

    ClipCard(
        clip = clip,
        highlightQuery = "示例 飞书",
        onPinToggle = {},
        onDelete = {},
        onCopy = {},
        onSwipePastAction = {},
        onClick = {},
        onLongClick = {},
        swipePastActionText = "继续右滑折叠数据",
        onKeepCurrentScrollPosition = {}
    )
}
