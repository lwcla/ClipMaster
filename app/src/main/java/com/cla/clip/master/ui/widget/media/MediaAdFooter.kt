package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ad.PageAdSlot

/**
 * 固定媒体广告的尺寸与释放边界；自然高度放不下时不放置素材，图片优先占用空间。
 * 超高状态按页面会话及广告位锁存，避免移除后重复加载；新访问或新业务阶段重新评估。
 */
@Composable
internal fun MediaAdFooter(
    session: PageAdSession,
    placement: AdPlacement,
    availableHeightPx: Int,
    minimumGridHeightPx: Int,
    blocked: Boolean,
    canReveal: Boolean,
    modifier: Modifier = Modifier,
) {
    // 同一访问的同一广告位不重试；阶段切换的独立广告位不能被前阶段超高素材锁死。
    var oversized by remember(session, placement) { mutableStateOf(false) }
    // 页面本身过小时不请求广告；已超高时移除子组合，让 PageAdSlot 释放原生资源。
    if (oversized || !MediaAdFooterPolicy.fits(availableHeightPx, 0, minimumGridHeightPx)) return

    Box(
        modifier = modifier.fillMaxWidth().layout { measurable, constraints ->
            // 固定宽度下读取素材完整高度，不能用剩余高度压缩或裁切 SDK 资产。
            val footer = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            // 本帧先决定是否放置，保证超高广告不会短暂把网格挤成零高度。
            val fits = MediaAdFooterPolicy.fits(availableHeightPx, footer.height, minimumGridHeightPx)
            if (!fits) {
                // 只从 false 变为 true；下一次组合释放子树，不会形成测量与状态反复切换。
                oversized = true
            }
            layout(footer.width, if (fits) footer.height else 0) {
                // 超高素材本帧不放置，也不提供可见/可点击的裁切广告区域。
                if (fits) footer.placeRelative(0, 0)
            }
        },
    ) {
        PageAdSlot(
            session = session,
            placement = placement,
            slotIndex = 0,
            requestEnabled = !blocked,
            canReveal = !blocked && canReveal,
            modifier = Modifier.padding(horizontal = 12.dp),
            // 固定槽没有 Lazy 离屏；小屏主动移除时及时销毁超高素材。
            retainOnDispose = false,
        )
    }
}
