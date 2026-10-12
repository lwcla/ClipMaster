package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged

/** 只测量真实剪贴卡片供置顶纠偏/锚点使用，不组合广告，也不改变 Lazy 业务 key 或索引。 */
@Composable
internal fun MeasuredClipContent(
    contentKey: Any,
    contentHeights: MutableMap<Any, Int>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 字典只保留组合中的卡片；离屏删除，长列表不累计全部历史高度。
    DisposableEffect(contentKey, contentHeights) {
        onDispose { contentHeights.remove(contentKey) }
    }
    // 测量仅写入当前卡片像素高度，不读取实体或发起业务副作用。
    Box(modifier.onSizeChanged { size -> contentHeights[contentKey] = size.height }) { content() }
}
