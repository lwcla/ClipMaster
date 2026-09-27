package com.cla.clip.master.ui.widget.ad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/**
 * 详情正文之前的广告布局外壳；只在素材实际占高时追加 16dp 正文分隔。
 * 外部测量包含广告与分隔的总高，便于滚动锚点补偿；零高广告不留下空白。
 * 内容由 slot 提供，本组件不持有会话、SDK、页面状态或点击处理。
 */
@Composable
internal fun AdHeaderLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth().layout { measurable, constraints ->
            // 顶部宿主按自然高度测量，禁用/失败的空广告不能继承最小高度。
            val header = measurable.measure(constraints.copy(minHeight = 0))
            // 分隔属于整个广告头；广告收起时一并归零，禁止依赖加载前的估算值。
            val bottomGap = if (header.height > 0) 16.dp.roundToPx() else 0
            layout(header.width, header.height + bottomGap) {
                // 广告保留原始坐标，间隔仅位于其下方，不裁切或覆盖 SDK 素材。
                header.placeRelative(0, 0)
            }
        },
    ) {
        content()
    }
}
