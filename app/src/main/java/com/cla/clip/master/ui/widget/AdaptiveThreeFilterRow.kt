package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 三筛选入口的通用布局骨架；只按实测文字最小宽度换行，不持有业务条件或菜单状态。 */
@Composable
internal fun AdaptiveThreeFilterRow(
    /** 第一个入口的完整摘要所需宽度。 */ leadingMinWidth: Dp,
    /** 中间入口完整摘要所需宽度。 */ middleMinWidth: Dp,
    /** 最后入口保留的基础摘要宽度，长名称允许自身省略。 */ trailingMinWidth: Dp,
    /** 外部控制边距，组件只负责内部 2:2:3 布局。 */ modifier: Modifier = Modifier,
    /** 首列放不下时独占第一行。 */ leadingContent: @Composable (Modifier) -> Unit,
    /** 换行后与末列继续使用 2:3 比例。 */ middleContent: @Composable (Modifier) -> Unit,
    /** 末列通常需要更多摘要空间。 */ trailingContent: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        /** 布局规则同时检查第一行和第二行，极窄屏下三项分别独占一行。 */
        val layout = resolveThreeFilterLayout(maxWidth.value, leadingMinWidth.value, middleMinWidth.value, trailingMinWidth.value, 8f)
        // 窄屏或大字体按真实内容换为两行或三行，不压缩点击热区。
        if (layout == ThreeFilterLayout.Row) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                leadingContent(Modifier.weight(2f))
                middleContent(Modifier.weight(2f))
                trailingContent(Modifier.weight(3f))
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                leadingContent(Modifier.fillMaxWidth())
                if (layout == ThreeFilterLayout.LeadingThenPair) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        middleContent(Modifier.weight(2f))
                        trailingContent(Modifier.weight(3f))
                    }
                } else {
                    middleContent(Modifier.fillMaxWidth())
                    trailingContent(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
