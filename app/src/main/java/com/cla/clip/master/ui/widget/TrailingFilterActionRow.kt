package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight

/**
 * 左侧动作与独立尾侧筛选的通用布局；空间不足时筛选另起尾侧对齐行。
 * slot 自行提供动作语义与最小热区，本层不认识选择集合、页面状态或业务实体。
 */
@Composable
internal fun TrailingFilterActionRow(
    // 主动作组可自行使用 FlowRow 换行；不会被尾侧控件挤成逐字排列。
    actions: @Composable () -> Unit,
    // 独立筛选控件，始终贴齐内容区尾侧边缘。
    filter: @Composable () -> Unit,
    // 宿主指定宽度和外边距，内部不添加页面专属留白。
    modifier: Modifier = Modifier,
) {
    Layout(content = { Box { actions() }; Box { filter() } }, modifier = modifier) { measurables, constraints ->
        // 两组都按完整可用宽度自然测量，保证决定换行前标签不被挤压。
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // 左侧动作组保留自身换行能力。
        val actionsPlaceable = measurables[0].measure(loose)
        // 尾侧筛选按实际主题字体和最小热区测量，不估算字符宽度。
        val filterPlaceable = measurables[1].measure(loose)
        // 同行至少留 12dp；换行后控件本身已包含 48dp 热区。
        val gap = 12.dp.roundToPx()
        // 有界工具栏占满宽度；无界 Preview 则以内容所需宽度为准。
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else actionsPlaceable.width + gap + filterPlaceable.width
        // 任何一组放不下时，筛选独立换行而不是紧跟最后一个按钮。
        val stacked = actionsPlaceable.width + gap + filterPlaceable.width > width
        // 两行自然高度相加；单行按较高控件居中对齐。
        val height = constraints.constrainHeight(if (stacked) actionsPlaceable.height + filterPlaceable.height
            else maxOf(actionsPlaceable.height, filterPlaceable.height))
        layout(width, height) {
            actionsPlaceable.placeRelative(0, if (stacked) 0 else (height - actionsPlaceable.height) / 2)
            filterPlaceable.placeRelative(width - filterPlaceable.width,
                if (stacked) actionsPlaceable.height else (height - filterPlaceable.height) / 2)
        }
    }
}
