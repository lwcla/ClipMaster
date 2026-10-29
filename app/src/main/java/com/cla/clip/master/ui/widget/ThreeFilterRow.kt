package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 三入口固定等宽同行，文字省略与完整读屏由各 slot 负责；不持有业务状态。 */
@Composable
internal fun ThreeFilterRow(
    /** 外部布局与边距。 */ modifier: Modifier = Modifier,
    /** 第一个入口，按调用方指定的视觉顺序渲染。 */ leadingContent: @Composable (Modifier) -> Unit,
    /** 中间入口，宽度与两侧一致。 */ middleContent: @Composable (Modifier) -> Unit,
    /** 最后入口，长值不能挤压前两项。 */ trailingContent: @Composable (Modifier) -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leadingContent(Modifier.weight(1f))
        middleContent(Modifier.weight(1f))
        trailingContent(Modifier.weight(1f))
    }
}
