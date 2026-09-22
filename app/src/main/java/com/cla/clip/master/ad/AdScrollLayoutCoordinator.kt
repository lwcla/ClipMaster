package com.cla.clip.master.ad

import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged

/** 连续滚动页顶部广告的布局协调器；返回的测量 Modifier 应挂在始终存在的零间距广告容器上。 */
@Composable
internal fun rememberTopAdScrollLayoutCoordinator(
    session: PageAdSession,
    scrollState: ScrollState,
    blocked: Boolean,
): Modifier {
    // 实测广告高度包含实际容器，加载失败时保持零高，不凭估算预留空间。
    var headerHeight by remember(scrollState) { mutableIntStateOf(0) }
    // 变化前的正文锚点以滚动像素和旧头高表示，不读取业务内容。
    var pendingAnchor by remember(scrollState) { mutableStateOf<Pair<Int, Int>?>(null) }
    // 同帧 SDK 回调和 Compose 测量共同推动一次补偿。
    var revision by remember(session) { mutableIntStateOf(0) }
    // 新手势或弹窗可以在等待布局的一帧期间到达，恢复前必须读取最新许可。
    val canMutate by rememberUpdatedState(!blocked && !scrollState.isScrollInProgress && !session.pointerPressed)
    SideEffect { session.canMutateLayout = !blocked && !scrollState.isScrollInProgress }
    DisposableEffect(session, scrollState) {
        session.onLayoutWillChange = {
            // 单顶部槽每次发布前取最新位置，避免上次无尺寸回调留下陈旧锚点。
            pendingAnchor = scrollState.value to headerHeight
        }
        session.onLayoutChanged = { revision++ }
        onDispose {
            session.onLayoutWillChange = null
            session.onLayoutChanged = null
        }
    }
    LaunchedEffect(session, revision, headerHeight, blocked, scrollState.isScrollInProgress, session.pointerPressed) {
        if (!canMutate) return@LaunchedEffect
        // 等待 AndroidView 和滚动范围完成测量；若用户开始新交互则延期补偿。
        withFrameNanos { }
        if (!canMutate) return@LaunchedEffect
        // 只有实际高度变化才补偿，避免 SDK 的无尺寸状态回调移动页面。
        val anchor = pendingAnchor ?: return@LaunchedEffect
        if (anchor.second != headerHeight) {
            scrollState.scrollTo(AdScrollAnchorPolicy.restoreOffset(anchor.first, anchor.second, headerHeight))
            pendingAnchor = null
        }
    }
    return Modifier.onSizeChanged { size ->
        if (size.height != headerHeight) {
            if (pendingAnchor == null) pendingAnchor = scrollState.value to headerHeight
            headerHeight = size.height
            revision++
        }
    }
}
