package com.cla.clip.master.ad

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/** 布局层只传递真实可见内容及位置，不让广告或加载尾项成为业务锚点。 */
private data class VisibleAdContent(
    /** 当前条目的稳定业务 key。 */
    val key: Any,
    /** 条目顶部相对于 Lazy 视口的像素坐标。 */
    val offset: Int,
)

/** 单列内容与广告共用一个 Lazy 容器索引，内容高度用于跳过只有广告尾部可见的条目。 */
@Composable
internal fun rememberListAdLayoutCoordinator(
    session: PageAdSession,
    listState: LazyListState,
    contentKeys: List<Any>,
    blocked: Boolean = false,
    pinRestorePending: Boolean = false,
    contentHeights: Map<Any, Int> = emptyMap(),
    lazyItemKeys: List<Any> = contentKeys,
    // 顶部广告页初始位于起点时保持起点，避免恢复首正文 key 把新广告直接滚出屏幕。
    keepStartAtTop: Boolean = false,
) {
    AdLayoutCoordinator(
        session = session,
        contentKeys = contentKeys,
        lazyItemKeys = lazyItemKeys,
        blocked = blocked,
        scrolling = listState.isScrollInProgress,
        scrollingNow = { listState.isScrollInProgress },
        pinRestorePending = pinRestorePending,
        visibleContent = {
            // 顶部广告在初始起点自然展开，不执行首正文 key 恢复导致页面自动跳过广告。
            if (keepStartAtTop && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                emptyList()
            } else {
            // 广告附着在业务容器尾部；若卡片已完全离开视口，选择下一张真实可见卡片。
            listState.layoutInfo.visibleItemsInfo.filter { item ->
                item.key in contentKeys &&
                    item.offset + (contentHeights[item.key] ?: item.size) > listState.layoutInfo.viewportStartOffset
            }.map { VisibleAdContent(it.key, it.offset) }
            }
        },
        requestScroll = { index, offset -> listState.requestScrollToItem(index, offset) },
    )
}

/** 网格视口因广告尺寸变化时保护图片锚点；固定底部广告不占 Lazy 索引，默认直接使用图片 key 顺序。 */
@Composable
internal fun rememberGridAdLayoutCoordinator(
    session: PageAdSession,
    gridState: LazyGridState,
    contentKeys: List<Any>,
    blocked: Boolean = false,
    lazyItemKeys: List<Any> = contentKeys,
    contentHeights: Map<Any, Int> = emptyMap(),
    // 首行多个业务 key 可共用一个 Lazy 容器，删除首图后仍能找到同行幸存内容。
    contentKeyToLazyKey: Map<Any, Any> = emptyMap(),
) {
    AdLayoutCoordinator(
        session = session,
        contentKeys = contentKeys,
        lazyItemKeys = lazyItemKeys,
        contentKeyToLazyKey = contentKeyToLazyKey,
        blocked = blocked,
        scrolling = gridState.isScrollInProgress,
        scrollingNow = { gridState.isScrollInProgress },
        pinRestorePending = false,
        visibleContent = {
            // 只选择视口中仍有实际图片高度的条目，避免边缘已离屏项成为恢复锚点。
            gridState.layoutInfo.visibleItemsInfo.filter { item ->
                item.key in contentKeys &&
                    item.offset.y + (contentHeights[item.key] ?: item.size.height) > gridState.layoutInfo.viewportStartOffset
            }.map { VisibleAdContent(it.key, it.offset.y) }
        },
        requestScroll = { index, offset -> gridState.requestScrollToItem(index, offset) },
    )
}

/** 注册宿主布局回调，批量合并同帧广告变化；置顶纠偏拥有更高优先级。 */
@Composable
private fun AdLayoutCoordinator(
    session: PageAdSession,
    contentKeys: List<Any>,
    lazyItemKeys: List<Any>,
    contentKeyToLazyKey: Map<Any, Any> = emptyMap(),
    blocked: Boolean,
    scrolling: Boolean,
    scrollingNow: () -> Boolean,
    pinRestorePending: Boolean,
    visibleContent: () -> List<VisibleAdContent>,
    requestScroll: (Int, Int) -> Unit,
) {
    // 回调始终读取最新快照，避免分页追加后用旧索引恢复视口。
    val currentContentKeys by rememberUpdatedState(contentKeys)
    // 网格的广告条目会占索引，恢复时需要最新完整顺序。
    val currentLazyKeys by rememberUpdatedState(lazyItemKeys)
    // 合并首行的业务身份映射到最新 Lazy 容器，锚点删除时不会跳过同行幸存图片。
    val currentContentToLazyKey by rememberUpdatedState(contentKeyToLazyKey)
    // 可见内容获取函数读取 Lazy 实时布局，而不是组合时的静态位置。
    val currentVisibleContent by rememberUpdatedState(visibleContent)
    // 恢复入口跟随当前 Lazy 状态实例，避免页面重建后写到旧实例。
    val currentRequestScroll by rememberUpdatedState(requestScroll)
    // 置顶纠偏优先权实时读取，广告恢复不得覆盖它。
    val currentPinPending by rememberUpdatedState(pinRestorePending)
    // 帧回调直接读取 Lazy 滚动状态，避免新手势先到而重组尚未刷新闭包的竞态。
    val currentScrollingNow by rememberUpdatedState(scrollingNow)
    // 业务交互和滚动共同决定是否可以改变布局；指针按压由宿主额外约束。
    val canMutate = AdListLayoutPolicy.canMutateLayout(scrolling, blocked, pinRestorePending)
    // 下一帧回调也必须复核交互状态，避免恢复抢走刚开始的新手势。
    val currentCanMutate by rememberUpdatedState(canMutate && !session.pointerPressed)
    // 首次变化保存的业务锚点；同帧多个槽位复用它，避免中间布局覆盖原位置。
    var pendingAnchor by remember(session) { mutableStateOf<AdListAnchor?>(null) }
    // 只有实际布局变更结束才递增，避免 will-change 单独触发恢复。
    var layoutRevision by remember(session) { mutableIntStateOf(0) }

    SideEffect { session.canMutateLayout = canMutate }
    DisposableEffect(session) {
        // 原生广告改高度前同步捕获，业务 key 仅驻留内存，不参与广告请求或日志。
        session.onLayoutWillChange = {
            if (pendingAnchor == null && !currentPinPending) {
                // 第一条真正可见的业务内容，广告项和分页状态不会出现在集合中。
                val visible = currentVisibleContent().firstOrNull()
                // 稳定 key 在最新去重顺序中的位置，用于后续原锚点删除时降级。
                val index = visible?.let { currentContentKeys.indexOf(it.key) } ?: -1
                if (index >= 0 && visible != null) {
                    pendingAnchor = AdListAnchor(currentContentKeys.toList(), index, -visible.offset)
                }
            }
        }
        // 一次实际高度变化完成，下一帧再按最新业务顺序恢复。
        session.onLayoutChanged = { layoutRevision++ }
        onDispose {
            session.onLayoutWillChange = null
            session.onLayoutChanged = null
        }
    }
    LaunchedEffect(session, layoutRevision, canMutate, pinRestorePending, session.pointerPressed) {
        if (pinRestorePending) {
            pendingAnchor = null
            return@LaunchedEffect
        }
        if (!canMutate || layoutRevision == 0) return@LaunchedEffect
        // 等广告布局应用，避免恢复请求先于 Native View 的高度变化生效。
        withFrameNanos { }
        if (!currentCanMutate || currentPinPending || session.pointerPressed || currentScrollingNow()) return@LaunchedEffect
        // 本轮待恢复锚点；没有真实内容或没有布局变化时保持 Lazy 默认行为。
        val anchor = pendingAnchor ?: return@LaunchedEffect
        // 先按真实内容解析下一/上一幸存项，再映射到 Lazy 条目索引。
        val target = AdListAnchorPolicy.resolve(anchor, currentContentKeys)
        if (target != null) {
            // 广告 key 与业务 key 分离，网格插入广告不会误定位广告自身。
            val contentKey = currentContentKeys[target.index]
            // 单列使用业务 key 本身；合并网格首行使用调用方提供的稳定容器 key。
            val lazyKey = currentContentToLazyKey[contentKey] ?: contentKey
            // 最新完整 Lazy 顺序决定真实滚动下标，广告自身始终不能成为业务锚点。
            val lazyIndex = currentLazyKeys.indexOf(lazyKey)
            if (lazyIndex >= 0) currentRequestScroll(lazyIndex, target.offset)
        }
        pendingAnchor = null
    }
}
