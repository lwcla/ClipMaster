package com.cla.clip.master.ui.page.list

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ReportGmailerrorred
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.cla.clip.base.general.config.ClipItemLeftClickAction
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.cardCornerShape
import com.cla.clip.master.ui.widget.rememberDeletedFormattedTime
import com.cla.clip.master.ui.widget.rememberFormattedTime
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
 * 普通列表按原剪贴时间展示；回收站按删除时间排序，因此需要切换为删除时间，避免排序依据和可见时间不一致。
 */
enum class ClipCardTimeMode {
    /** 展示剪贴记录原始时间。 */
    ClipTime,

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
    leftClickAction: ClipItemLeftClickAction = ClipItemLeftClickAction.None,
    enableLeftClickAction: Boolean = false,
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
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp))
            }
        }

        else -> {
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pagedClips.itemCount > 0) {
                    items(
                        count = pagedClips.itemCount,
                        // Paging key 使用数据库主键，保证置顶、删除、搜索条件变化和侧滑状态保存时 Compose 复用稳定。
                        key = pagedClips.itemKey { it.id },
                        contentType = pagedClips.itemContentType { "ClipCard" }
                    ) { index ->
                        val clip = pagedClips[index]
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
                                leftClickAction = leftClickAction,
                                enableLeftClickAction = enableLeftClickAction,
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

                when (pagedClips.loadState.append) {
                    is LoadState.Loading -> {
                        item {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                                    .size(26.dp)
                            )
                        }
                    }

                    is LoadState.Error -> {
                        item {
                            Text(
                                text = stringResource(com.cla.clip.base.general.R.string.base_general_data_load_failed_retry),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = {
                                        // append 加载失败时直接使用 Paging 的 retry，避免页面自己维护重试状态。
                                        pagedClips.retry()
                                    })
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

/**
 * 显示单个剪贴板内容的卡片。
 *
 * 卡片本身只处理展示和用户点击回调，不直接访问 ViewModel 或 Repository。
 *
 * 普通列表和普通搜索可以按设置开启左右分区：左半区执行页面传入的动作，
 * 右半区进入详情；折叠列表、折叠搜索和回收站保持整卡点击语义。
 * 左半区背景只作为底层视觉提示，内容始终按完整卡片宽度铺满。
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
    leftClickAction: ClipItemLeftClickAction = ClipItemLeftClickAction.None,
    enableLeftClickAction: Boolean = false,
    openedMenuClipId: Long? = null,
    onMenuActive: (Long) -> Unit = {},
    onMenuInactive: (Long) -> Unit = {},
) {
    val appColor = clip.appColor ?: MaterialTheme.colorScheme.outlineVariant
    val borderColor = appColor.copy(alpha = 0.3f)
    // 左半区面积较大，背景只保留很轻的同源提示色，避免压住铺满整卡的剪贴内容。
    val leftActionBackgroundColor = appColor.copy(alpha = 0.02f)
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
    val foldDescription = stringResource(com.cla.clip.base.general.R.string.base_general_fold_clip)
    val leftClickDescription = when (leftClickAction) {
        ClipItemLeftClickAction.Copy -> copyDescription
        ClipItemLeftClickAction.Pin -> pinDescription
        ClipItemLeftClickAction.Delete -> deleteDescription
        ClipItemLeftClickAction.Fold -> foldDescription
        ClipItemLeftClickAction.None -> detailDescription
    }
    val canRunLeftClickAction = enableLeftClickAction &&
        leftClickAction != ClipItemLeftClickAction.None &&
        when (leftClickAction) {
            ClipItemLeftClickAction.Copy -> onCopy != null
            ClipItemLeftClickAction.Pin -> onPinToggle != null
            ClipItemLeftClickAction.Delete -> onDelete != null
            ClipItemLeftClickAction.Fold -> onSwipePastAction != null
            ClipItemLeftClickAction.None -> false
        }
    val currentOnMenuActive by rememberUpdatedState(onMenuActive)
    val currentOnMenuInactive by rememberUpdatedState(onMenuInactive)
    // 外层 Card、侧滑内容和边框共用同一个圆角，保证阴影、水波纹和裁剪视觉一致。
    val cardShape = cardCornerShape

    /** 执行普通列表/普通搜索左半区动作，具体业务仍由页面层回调决定。 */
    fun runLeftClickAction() {
        when (leftClickAction) {
            ClipItemLeftClickAction.Copy -> onCopy?.invoke(clip)
            ClipItemLeftClickAction.Pin -> {
                if (clip.isPinned) {
                    onKeepCurrentScrollPosition()
                }
                onPinToggle?.invoke(clip)
            }

            ClipItemLeftClickAction.Delete -> {
                // 删除动作只触发页面层现有删除选择弹窗，这是防止误触后静默删除的安全边界。
                onDelete?.invoke(clip)
            }

            ClipItemLeftClickAction.Fold -> onSwipePastAction?.invoke(clip)
            ClipItemLeftClickAction.None -> onClick(clip)
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
            .clip(cardShape)
            .clipToBounds()
    ) {
        if (showActionMenu) {
            // 左侧操作区固定贴在 item 左边，内容卡片右滑后露出复制、置顶和删除按钮；左滑方向留给首页 Pager 切到“我的”。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.CenterStart),
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
                                onCopy?.invoke(clip)
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
                                if (clip.isPinned) {
                                    onKeepCurrentScrollPosition()
                                }
                                offsetPx = 0f
                                currentOnMenuInactive(clip.id)
                                onPinToggle?.invoke(clip)
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
                            painterRes = R.drawable.host_icon_delete,
                            onClick = {
                                offsetPx = 0f
                                currentOnMenuInactive(clip.id)
                                onDelete?.invoke(clip)
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

        ElevatedCard(
            shape = cardShape,
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = offsetDp)
                .pointerInput(clip.id, maxOffsetPx, swipePastDragMaxPx, swipePastTriggerPx, swipePastActionText) {
                    detectRightSwipeMenuGestures(
                        isMenuOpened = { offsetPx > 0f },
                        isAnimating = { isSwipeOffsetAnimating },
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
                                        onSwipePastAction?.invoke(clip)
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
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .border(1.dp, borderColor, cardShape)
            ) {
                if (canRunLeftClickAction) {
                    Row(
                        modifier = Modifier.matchParentSize()
                    ) {
                        Box(
                            modifier = Modifier
                                // 左半区底色只提示“这里有分区动作”，不参与内容测量，也不遮挡随后绘制的文本。
                                .weight(1f)
                                .fillMaxHeight()
                                .background(leftActionBackgroundColor)
                        )

                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        // 内容层始终使用整张卡片宽度，避免左半区动作改变文本排版和关键词高亮结果。
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ClipContent(clip, highlightQuery)

                        Spacer(Modifier.height(8.dp))
                        SourceAppNameWithTime(clip, highlightQuery, timeMode)
                    }
                }

                if (canRunLeftClickAction) {
                    Row(
                        modifier = Modifier.matchParentSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // 左半区透明热区承接设置动作；长按仍透出为整卡长按，避免左右区域行为割裂。
                                .combinedClickable(
                                    onClickLabel = leftClickDescription,
                                    onClick = { runLeftClickAction() },
                                    onLongClick = { onLongClick(clip) }
                                )
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // 右半区固定进入详情，与设置项无关，保证普通列表的主路径稳定。
                                .combinedClickable(
                                    onClickLabel = detailDescription,
                                    onClick = { onClick(clip) },
                                    onLongClick = { onLongClick(clip) }
                                )
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            // 未启用分区时使用整卡点击语义；“无”设置、折叠列表和回收站都会走这里。
                            .combinedClickable(
                                onClickLabel = detailDescription,
                                onClick = { onClick(clip) },
                                onLongClick = { onLongClick(clip) }
                            )
                    )
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
                            // 置顶角标是卡片内的最高装饰层；放在内容、复制热区和选中态之后绘制，避免被后续兄弟节点盖住。
                            .zIndex(2f)
                            .width(42.dp)
                            .align(Alignment.TopEnd)
                            .alpha(0.6f),
                        tint = appColor
                    )
                }
            }
        }
    }
}

/**
 * 剪贴 item 的右滑菜单手势。
 *
 * 关闭状态下只有向右拖过触摸阈值才消费事件并打开菜单；向左拖动会留给外层首页 Pager，
 * 让列表页继续支持左滑切到“我的”。菜单已展开或动画中时，左右拖动都由 item 接管，
 * 其中左滑优先收回菜单，避免带着展开菜单直接切换页面。
 *
 * @param isMenuOpened 当前 item 菜单是否已经露出，决定左滑是否由 item 优先消费。
 * @param isAnimating 偏移动画是否正在执行，动画中继续消费横向手势，避免父级 Pager 抢占。
 * @param onDrag 接收本次横向拖动增量，正数表示右滑展开，负数表示左滑收回。
 * @param onDragEnd 用户正常松手后的吸附或折叠判断入口。
 * @param onDragCancel 手势被父级或系统取消时的兜底吸附入口。
 */
private suspend fun PointerInputScope.detectRightSwipeMenuGestures(
    isMenuOpened: () -> Boolean,
    isAnimating: () -> Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var initialOverSlop = 0f
        var hasAcceptedGesture = false
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
            // 关闭状态下只接受右滑；菜单已展开或动画中时，左滑也要由 item 消费来完成收回或防止切页。
            val shouldHandleDrag = isMenuOpened() || isAnimating() || overSlop > 0f
            if (shouldHandleDrag) {
                hasAcceptedGesture = true
                initialOverSlop = overSlop
                change.consume()
            }
        }

        if (drag != null && hasAcceptedGesture) {
            onDrag(initialOverSlop)
            val finishedNormally = horizontalDrag(drag.id) { change ->
                onDrag(change.positionChange().x)
                change.consume()
            }
            if (finishedNormally) {
                onDragEnd()
            } else {
                onDragCancel()
            }
        }
    }
}

/** 剪贴数据来源 App 和时间，普通列表展示写入时间，回收站展示删除时间。 */
@Composable
private fun SourceAppNameWithTime(
    clip: ClipShowEntity,
    highlightQuery: String?,
    timeMode: ClipCardTimeMode,
) {
    val currentDensity = LocalDensity.current
    val timeText = when (timeMode) {
        ClipCardTimeMode.ClipTime -> clip.rememberFormattedTime()
        ClipCardTimeMode.DeletedTime -> clip.rememberDeletedFormattedTime(
            prefix = stringResource(com.cla.clip.base.general.R.string.base_general_deleted_at_prefix)
        )
    }
    // 来源 App 与时间区域空间很窄，锁定 fontScale 可以避免系统大字体下挤压到不可读。
    CompositionLocalProvider(LocalDensity provides Density(density = currentDensity.density, fontScale = 1f)) {
        Layout(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = clip.appIconPath,
                        contentDescription = null,
                        placeholder = rememberVectorPainter(Icons.Filled.ReportGmailerrorred),
                        error = rememberVectorPainter(Icons.Filled.ReportGmailerrorred),
                        modifier = Modifier.size(15.dp),
                        contentScale = ContentScale.Crop,
                        colorFilter = if (clip.appIconPath.isNullOrBlank()) {
                            // 缺少真实图标时使用错误色提示来源未知，真实图标则保持原始颜色。
                            ColorFilter.tint(MaterialTheme.colorScheme.error)
                        } else {
                            null
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    HighlightableText(
                        text = clip.appName ?: stringResource(com.cla.clip.base.general.R.string.base_general_unknow),
                        highlightQuery = highlightQuery,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val leftNode = measurables[0]
            val rightNode = measurables[1]

            // 时间最多占一半宽度，优先保留来源 App 图标和名称的识别空间。
            val maxTimeWidth = (constraints.maxWidth * 0.5f).toInt()
            val rightPlaceable = rightNode.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = maxTimeWidth
                )
            )

            // 左右之间固定留 8dp 间隔，避免长 App 名和长时间文本贴在一起。
            val remainingWidth = constraints.maxWidth - rightPlaceable.width - 8.dp.roundToPx()
            val leftMaxWidth = max(0, remainingWidth)
            val leftPlaceable = leftNode.measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = leftMaxWidth
                )
            )

            val height = max(leftPlaceable.height, rightPlaceable.height)
            layout(constraints.maxWidth, height) {
                leftPlaceable.placeRelative(0, (height - leftPlaceable.height) / 2)
                rightPlaceable.placeRelative(constraints.maxWidth - rightPlaceable.width, (height - rightPlaceable.height) / 2)
            }
        }
    }
}

/**
 * 剪贴数据的内容显示。
 *
 * 链接预览、链接标题和链接地址优先集中显示在顶部，原始剪贴字符串始终保留三行以内，
 * 让列表 item 在保留必要上下文的同时不会被长文本撑得过高。
 */
@Composable
private fun ClipContent(clip: ClipShowEntity, highlightQuery: String?) {
    Column {
        LinkPreviewContent(clip, highlightQuery)

        HighlightableText(
            text = clip.content,
            highlightQuery = highlightQuery,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3
        )
    }
}

/**
 * 链接预览内容区。
 *
 * 只要记录里存在链接、标题或预览图就展示该区域；如果剪贴字符串本身就是链接，
 * 链接地址不再重复显示，避免一个 item 内出现两段完全相同的 URL。
 */
@Composable
private fun LinkPreviewContent(clip: ClipShowEntity, highlightQuery: String?) {
    val link = clip.link?.takeIf { it.isNotBlank() }
    val title = clip.linkTitle?.takeIf { it.isNotBlank() }
    val imageUrl = clip.linkImgUrl?.takeIf { it.isNotBlank() }
    val shouldShowLink = link != null && link != clip.content

    if ((link == null || !shouldShowLink) && title == null && imageUrl == null) {
        return
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (title != null) {
                        HighlightableText(
                            text = title,
                            highlightQuery = highlightQuery,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2
                        )
                    }

                    if (shouldShowLink) {
                        if (title != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        HighlightableText(
                            text = link.orEmpty(),
                            highlightQuery = highlightQuery,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 侧滑露出的单个操作按钮。
 *
 * 按钮高度由外层 item 决定，图标始终放在点击区域中心；点击区域保持透明，
 * 让侧滑操作区沿用卡片背后的整体底色。
 */
@Composable
private fun SwipeActionButton(
    painterRes: Int,
    iconContentDescription: String,
    iconTint: Color,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(painterRes),
            contentDescription = iconContentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 支持搜索关键词高亮的文本。
 *
 * 高亮只处理用户看得见的字段，匹配词由搜索页传入；普通列表页传 null 时不会额外分配高亮片段。
 */
@Composable
private fun HighlightableText(
    text: String,
    highlightQuery: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val highlightTextColor = MaterialTheme.colorScheme.onSurface
    val highlightedText = remember(text, highlightQuery, highlightColor, highlightTextColor) {
        text.highlightedBy(
            query = highlightQuery,
            highlightStyle = SpanStyle(
                color = highlightTextColor,
                background = highlightColor
            )
        )
    }

    Text(
        text = highlightedText,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 根据搜索词生成高亮文本。
 *
 * 这里按空白拆词、去重并做大小写不敏感匹配；如果多个词重叠，只给尚未覆盖的位置加样式，
 * 这样能避免重复添加 Span 造成样式边界不可预测。
 */
private fun String.highlightedBy(query: String?, highlightStyle: SpanStyle): AnnotatedString {
    val words = query
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinctBy { it.lowercase() }
        .orEmpty()

    if (isBlank() || words.isEmpty()) {
        return AnnotatedString(this)
    }

    return buildAnnotatedString {
        append(this@highlightedBy)
        val lowerText = this@highlightedBy.lowercase()
        val highlightedRanges = mutableListOf<IntRange>()
        words.forEach { word ->
            val lowerWord = word.lowercase()
            var startIndex = lowerText.indexOf(lowerWord)
            while (startIndex >= 0) {
                val endExclusive = startIndex + lowerWord.length
                val range = startIndex until endExclusive
                val hasOverlap = highlightedRanges.any { existing ->
                    range.first <= existing.last && range.last >= existing.first
                }
                if (!hasOverlap) {
                    addStyle(highlightStyle, startIndex, endExclusive)
                    highlightedRanges += range
                }
                startIndex = lowerText.indexOf(lowerWord, startIndex + lowerWord.length)
            }
        }
    }
}

/** 空状态屏幕。 */
@Composable
private fun EmptyScreen(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(widthDp = 150, heightDp = 300)
@Composable
private fun ClipCardPreview() {
    val clip = ClipShowEntity(
        id = 1L,
        content = "这是一个示例剪贴板内容，用于预览ClipCard组件的显示效果。用于预览ClipCard组件的显示效果。",
        timestamp = System.currentTimeMillis(),
        deletedAt = System.currentTimeMillis(),
        formattedTime = "刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚刚",
        appName = "飞书飞书飞书飞书",
        appIconPath = "https://img2.baidu.com/it/u=3546907450,5411894&fm=253&fmt=auto&app=120&f=JPEG?w=500&h=500",
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
