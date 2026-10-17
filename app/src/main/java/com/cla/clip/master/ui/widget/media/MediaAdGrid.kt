package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.cla.clip.master.ad.PageAdSession
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ad.rememberGridAdLayoutCoordinator
import com.cla.clip.master.ad.rememberPageAdSession

/**
 * 四列图片区域独立滚动，唯一广告容器固定在网格外下方，不参与图片索引、选择或计数。
 * 页面提供已有会话时复用整页触摸守卫；独立调用才自行管理会话，离开页面仍按原生命周期释放。
 */
@Composable
internal fun <T> MediaAdGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    sessionKey: Any,
    placement: AdPlacement?,
    blocked: Boolean,
    adSession: PageAdSession? = null,
    modifier: Modifier = Modifier,
    // 阶段切换可沿用页面滚动状态；独立调用保留原先的默认保存边界。
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    itemContent: @Composable (T) -> Unit,
) {
    // 优先使用页面共享会话；独立调用才按阶段创建会话，广告位身份负责隔离各阶段请求。
    val session = adSession ?: rememberPageAdSession(sessionKey to placement, enabled = placement != null)
    // 同一快照同时提供 Lazy 数量、身份和媒体值，禁止延迟回调再访问持续变化的原列表。
    val renderedItems = snapshotMediaGridItems(items, itemKey)
    // 每张图片都是独立 Lazy 项，首行不再共用身份，广告也不再占用网格索引。
    val contentKeys = renderedItems.map { it.key }
    // 页面预览或提交状态撤销广告资格，沿用会话的资源释放及安全收起规则。
    SideEffect { session.suppressed = blocked }
    // 底部广告改变图片视口高度时仍恢复可见业务锚点；滚动和按压中延迟尺寸变化。
    rememberGridAdLayoutCoordinator(session, gridState, contentKeys, blocked = blocked)
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().then(if (adSession == null) Modifier.adInteractionGuard(session) else Modifier),
    ) {
        // 图片至少保留 96dp 视口，覆盖上下留白及触摸区域；更小窗口直接不请求底部广告。
        val minimumGridHeightPx = with(LocalDensity.current) { 96.dp.roundToPx() }
        // 在约束作用域内捕获可用高度，避免内层 Column 的布局作用域遮蔽该接收者。
        val availableHeightPx = constraints.maxHeight
        Column(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.Top,
                // 只有能完整放下的广告才占高度，剩余空间全部交给图片；两者不叠放。
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                // 快照项自带稳定 key 与内容，Lazy 回调不依赖跨列表的 index 配对。
                items(items = renderedItems, key = { it.key }, contentType = { "media" }) { entry ->
                    Box(Modifier.padding(bottom = 10.dp)) { itemContent(entry.media) }
                }
            }
            // 广告为网格之外的唯一固定容器，空态、失败和空间不足时零占位。
            if (renderedItems.isNotEmpty() && placement != null) {
                MediaAdFooter(
                    session = session,
                    placement = placement,
                    availableHeightPx = availableHeightPx,
                    minimumGridHeightPx = minimumGridHeightPx,
                    blocked = blocked,
                    canReveal = !gridState.isScrollInProgress,
                )
            }
        }
    }
}
