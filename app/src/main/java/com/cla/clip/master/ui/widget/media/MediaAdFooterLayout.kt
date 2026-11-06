package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints

/** 只按真实素材测量底栏；纵向间隔随素材归零，完整高度参与业务视口预算。 */
@Composable
internal fun MediaAdFooterLayout(
    // 标题和操作区之外，广告与业务内容共享的有界高度。
    availableHeightPx: Int,
    // 必须留给业务内容的最小可操作视口，单位为像素。
    minimumContentHeightPx: Int,
    // 由调用方指定的外部留白，不进入 SDK 素材内部。
    contentPadding: PaddingValues,
    // 实际高度超限时通知宿主锁存并释放，本层不保存广告状态。
    onOversized: () -> Unit,
    // 外层修饰符用于调用方尺寸和语义，不改变素材测量契约。
    modifier: Modifier = Modifier,
    // 实际广告槽；空内容必须自然测得零高度。
    content: @Composable () -> Unit,
) {
    // 横向留白遵循布局方向，纵向留白只在子内容确实占高时添加。
    val direction = LocalLayoutDirection.current
    Box(modifier.fillMaxWidth().layout { measurable, constraints ->
        // 自然测量完整素材，不以剩余高度裁切 SDK 内容。
        val footer = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        // 素材不存在或不可呈现时不留下顶部和底部白边。
        val top = if (footer.height > 0) contentPadding.calculateTopPadding().roundToPx() else 0
        // 底部留白同样随素材消失，兼容其他媒体调用方。
        val bottom = if (footer.height > 0) contentPadding.calculateBottomPadding().roundToPx() else 0
        // 宿主总高包含实际纵向留白，交给既有空间规则统一判断。
        val totalHeight = footer.height + top + bottom
        // 空间不足时本帧不呈现，绝不压缩素材或侵占业务最小视口。
        val fits = MediaAdFooterPolicy.fits(availableHeightPx, totalHeight, minimumContentHeightPx)
        if (!fits) onOversized()
        layout(footer.width, if (fits) totalHeight else 0) {
            // 超高素材本帧不放置，由会话宿主下一帧释放资源。
            if (fits && footer.height > 0) footer.placeRelative(0, top)
        }
    }) {
        Box(Modifier.padding(start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction))) { content() }
    }
}
