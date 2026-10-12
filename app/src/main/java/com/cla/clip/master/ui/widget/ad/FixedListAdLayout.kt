package com.cla.clip.master.ui.widget.ad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ad.PageAdSlot
import com.cla.clip.master.ui.widget.media.MediaAdFooterPolicy

/**
 * 固定单广告与列表为兄弟节点，素材和业务数据独立准备；内容只负责持续呈现资格。
 * 自然高度不足以保留 96dp 可操作列表时本访问收起超高素材，复用媒体固定槽的空间规则。
 */
@Composable
internal fun FixedListAdLayout(
    session: PageAdSession,
    placement: AdPlacement,
    contentState: ListAdContentState,
    eligible: Boolean = true,
    blocked: Boolean = false,
    canReveal: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    // 超高素材只在当前访问/位置锁存；普通空态、查询变化和重组均不能触发重新请求。
    var oversized by remember(session, placement) { mutableStateOf(false) }
    // 遮挡只暂停请求与点击，不将空列表写入 enabled、eligible 或 suppressed。
    SideEffect { session.suppressed = blocked }
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        // 与固定图片广告使用相同最小业务视口，按窗口当前密度转换，不预留广告空白高度。
        val minimumContentHeight = 96.dp.roundToPx()
        // 固定区域只有有界窗口才做空间保护；列表子树始终使用剩余真实高度。
        val availableHeight = constraints.maxHeight
        // 键盘/分屏缩小时即使素材当前隐藏，也要区分已申请的槽和初次进入的小窗口。
        val hasResource = "${placement.placementId}:0" in session.residentKeys
        if (FixedAdSpacePolicy.mustStopSlot(availableHeight, 0, minimumContentHeight, hasResource)) {
            oversized = true
        }
        // 同一广告宿主在 loading/empty 期间也存在，因此请求不等待真实列表结果。
        val header = subcompose("ad") {
            if (!oversized && MediaAdFooterPolicy.fits(availableHeight, 0, minimumContentHeight)) {
                PageAdSlot(session, placement, eligible = eligible, requestEnabled = !blocked,
                    canReveal = canReveal && !blocked,
                    contentPresentable = contentState.containsContent,
                    modifier = Modifier.padding(horizontal = 12.dp), retainOnDispose = false)
            }
        }.firstOrNull()?.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity))
        // 广告真实占高后才追加与列表的间距，按钮和所有外围间距随空态一起归零。
        val headerHeight = header?.height ?: 0
        // 十 dp 是现有记录列表的内容分隔，不属于 SDK 资产，不覆盖广告点击区域。
        val gap = if (headerHeight > 0) 10.dp.roundToPx() else 0
        // 首次测量先决定是否放置，避免一帧把业务视口挤成零高；下一次组合释放超高资源。
        val fits = MediaAdFooterPolicy.fits(availableHeight, headerHeight + gap, minimumContentHeight)
        if (FixedAdSpacePolicy.mustStopSlot(availableHeight, headerHeight + gap, minimumContentHeight, hasResource)) {
            oversized = true
        }
        // 超高素材、无填充及空态全部零占位，业务列表立即取得全部可用空间。
        val occupiedHeight = if (fits) headerHeight + gap else 0
        // 内容 slot 不持有页面导航/业务实体，调用方通过该 Modifier 使用剩余高度。
        val body = subcompose("content") { Box { content(Modifier.fillMaxSize()) } }.single().measure(
            constraints.copy(minHeight = 0, maxHeight = (availableHeight - occupiedHeight).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.constrainHeight(occupiedHeight + body.height)) {
            if (fits) header?.placeRelative(0, 0)
            body.placeRelative(0, occupiedHeight)
        }
    }
}
