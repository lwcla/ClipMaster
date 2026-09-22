package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onSizeChanged
import com.cla.clip.master.ad.PageAdSession
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ad.ListAdPlacementPolicy
import com.cla.clip.master.ad.PageAdSlot
import com.cla.clip.master.ad.adInteractionGuard
import com.cla.clip.master.ad.rememberGridAdLayoutCoordinator
import com.cla.clip.master.ad.rememberPageAdSession

/** 四列媒体网格；业务项和整行广告独立命名，广告永不进入选择集合或计数。 */
@Composable
internal fun <T> MediaAdGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    sessionKey: Any,
    placement: AdPlacement?,
    blocked: Boolean,
    adSession: PageAdSession? = null,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    // 优先使用页面共享会话；独立调用才按阶段创建会话，广告位身份负责隔离各阶段请求。
    val session = adSession ?: rememberPageAdSession(sessionKey to placement, enabled = placement != null)
    // 滚动身份独立于广告加载状态，结果切换时由协调器保持业务锚点。
    val gridState = rememberLazyGridState()
    // 业务 key 显式命名空间化，避免广告索引与数据库 id 冲突。
    val contentKeys = items.map { "media:${itemKey(it)}" }
    // 四列的完整首行后开始，每二十张实际媒体再次插入，不设置页面总量上限。
    val anchors = if (placement == null) emptyList() else ListAdPlacementPolicy.anchorIndices(items.size, columns = 4)
    // 位置序号建索引，长页面追加候选时避免反复线性查找广告锚点。
    val anchorSlots = anchors.withIndex().associate { it.value to it.index }
    // 首行最多四张媒体共用一项，首槽因此随首行内容立即组合，不依赖广告本身进入预取区。
    val firstRowCount = minOf(4, items.size)
    // 锚点保留全部真实图片；前四张映射到当前首行，首图删除后仍能选择最近幸存图片。
    val contentKeyToLazyKey = MediaGridAnchorPolicy.lazyKeyMap(contentKeys)
    // 首行业务高度用于区分“图片仍可见”与“只剩广告尾部可见”。
    val contentHeights = remember(session) { mutableStateMapOf<Any, Int>() }
    // 首槽内嵌在首行尾部；后续槽仍为独立全宽项，完整顺序用于精确恢复业务锚点。
    val lazyKeys = buildList<Any> {
        contentKeys.forEachIndexed { index, key ->
            if (index == 0 || index >= firstRowCount) {
                add(key)
                if (index >= firstRowCount) anchorSlots[index]?.let { slotIndex -> add("ad:$placement:$slotIndex") }
            }
        }
    }
    SideEffect { session.suppressed = blocked }
    rememberGridAdLayoutCoordinator(session, gridState, contentKeys, blocked = blocked,
        lazyItemKeys = lazyKeys, contentHeights = contentHeights, contentKeyToLazyKey = contentKeyToLazyKey)
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.Top,
        modifier = modifier.fillMaxSize().then(if (adSession == null) Modifier.adInteractionGuard(session) else Modifier),
    ) {
        if (items.isNotEmpty()) {
            item(key = contentKeys.first(), contentType = "first_media_row", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().onSizeChanged { contentHeights[contentKeys.first()] = it.height }) {
                        repeat(4) { index ->
                            if (index < firstRowCount) {
                                key(contentKeys[index]) {
                                    Box(Modifier.weight(1f).padding(bottom = 10.dp)) { itemContent(items[index]) }
                                }
                            } else {
                                // 短列表保留四列缩略图宽度，空位没有身份、计数或交互。
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (placement != null) {
                        PageAdSlot(session, placement, slotIndex = 0, requestEnabled = !blocked,
                            canReveal = !blocked && !gridState.isScrollInProgress && gridState.firstVisibleItemIndex == 0)
                    }
                }
            }
        }
        items.forEachIndexed { index, media ->
            if (index >= firstRowCount) {
                item(key = contentKeys[index], contentType = "media") {
                    Box(Modifier.padding(bottom = 10.dp)) { itemContent(media) }
                }
                // 后续广告身份只依赖阶段及位置，候选追加和任务进度不触发重载。
                val slotIndex = anchorSlots[index]
                if (slotIndex != null && placement != null) {
                    // 广告 key 独立于业务 key，也用于禁止在视口上方迟到插入。
                    val adKey = "ad:$placement:$slotIndex"
                    item(key = adKey, contentType = "ad", span = { GridItemSpan(maxLineSpan) }) {
                        PageAdSlot(
                            session = session,
                            placement = placement,
                            slotIndex = slotIndex,
                            requestEnabled = !blocked && gridState.layoutInfo.visibleItemsInfo.any { it.key == contentKeys[index] },
                            canReveal = !blocked && !gridState.isScrollInProgress && lazyKeys.indexOf(adKey) >= gridState.firstVisibleItemIndex,
                        )
                    }
                }
            }
        }
    }
}
