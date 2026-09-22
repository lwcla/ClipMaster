package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.ListAdPlacementPolicy
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ad.PageAdSlot

/** 剪贴及下载记录列表的广告配置，只包含展示契约，不携带业务正文。 */
internal data class ClipListAdConfig(
    /** 页面可见会话；查询变化由页面更换会话，分页追加保持会话不变。 */
    val session: PageAdSession,
    /** 已批准的列表广告位置。 */
    val placement: AdPlacement,
    /** 管理多选、弹窗或预览时禁止广告请求及交互。 */
    val suppressed: Boolean = false,
    /** 搜索防抖未结束时禁止请求，已加载业务结果仍正常显示。 */
    val requestEnabled: Boolean = true,
    /** 页面自有动画或确认操作期间不允许改变布局。 */
    val layoutBlocked: Boolean = false,
)

/**
 * 业务内容独立包裹，广告位于其外侧尾部；保留 Lazy 业务 key 和索引，避免影响分页和置顶纠偏。
 * 只在真实广告有高度时附加间距，加载失败和未请求时没有空白占位。
 */
@Composable
internal fun ListAdContentItem(
    contentKey: Any,
    contentIndex: Int,
    listState: LazyListState,
    adConfig: ClipListAdConfig?,
    contentHeights: MutableMap<Any, Int>,
    // 敏感内容只提供本地布尔资格，正文不会进入广告宿主。
    slotEligible: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 测量字典只保留组合中的卡片，避免长时间滚动时累计所有历史高度。
    DisposableEffect(contentKey, contentHeights) {
        onDispose { contentHeights.remove(contentKey) }
    }
    Column(modifier) {
        Box(Modifier.onSizeChanged { size -> contentHeights[contentKey] = size.height }) {
            content()
        }
        // 复用纯位置策略，只做常数时间查询，不为每个可见 item 分配完整广告位置表。
        val slotIndex = ListAdPlacementPolicy.singleColumnSlotIndex(contentIndex)
        if (adConfig != null && slotIndex != null) {
            // 首位随首批数据准备，后续必须对应真实业务卡片已进入视口，Lazy 预取不触发广告。
            val anchorLayout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == contentKey }
            // 只使用卡片高度，广告尾部单独可见不能冒充锚点内容仍可见。
            val anchorHeight = contentHeights[contentKey] ?: anchorLayout?.size
            // 布局视口以像素表达边界；不读取或传递卡片内容、id 等用户数据。
            val requestVisible = ListAdPlacementPolicy.shouldRequestSlot(
                slotIndex, anchorLayout?.offset, anchorHeight,
                listState.layoutInfo.viewportStartOffset, listState.layoutInfo.viewportEndOffset,
            )
            key(adConfig.session, adConfig.placement, slotIndex) {
                PageAdSlot(
                    session = adConfig.session,
                    placement = adConfig.placement,
                    slotIndex = slotIndex,
                    eligible = !adConfig.suppressed &&
                        ListAdPlacementPolicy.singleColumnSlotIndex(contentIndex, slotEligible) != null,
                    requestEnabled = adConfig.requestEnabled && requestVisible,
                    canReveal = !adConfig.layoutBlocked,
                    modifier = Modifier.layout { measurable, constraints ->
                        // SDK 实际测量高度决定留白，零高槽不产生额外列表间距。
                        val placeable = measurable.measure(constraints)
                        // 只有已展示广告才有卡片与广告之间的十 dp 分隔。
                        val topGap = if (placeable.height > 0) 10.dp.roundToPx() else 0
                        layout(placeable.width, placeable.height + topGap) {
                            placeable.placeRelative(0, topGap)
                        }
                    },
                )
            }
        }
    }
}
