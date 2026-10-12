package com.cla.clip.master.ui.widget.clip

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ad.DetailAdSensitivityPolicy
import com.cla.clip.master.ui.widget.ad.ListAdContentState

/**
 * 剪贴列表与固定广告共同消费的当前展示快照，只在编译期调用，不是外部协议。
 * 渲染索引保留 Paging 的真实位置；广告宿主只接收内容状态和敏感许可，不接收这些索引或正文。
 */
internal data class ClipListAdPresentation(
    /** 去重后的真实业务条目，placeholder 不会进入该集合。 */
    val renderEntries: List<ClipPagingRenderEntry>,
    /** 当前画面有真实记录、仅加载画面或没有内容，不保存上次非空状态。 */
    val contentState: ListAdContentState,
    /** 本地可见正文通过既有敏感规则后得到的低敏资格。 */
    val eligible: Boolean,
)

/**
 * 从共享 Paging 快照与当前视口同步读取广告展示许可，不创建额外查询或状态复制协程。
 * 搜索历史态传入空来源，立即表达无结果内容，但外层广告请求宿主仍可保持组合。
 */
@Composable
internal fun rememberClipListAdPresentation(
    pagedClips: LazyPagingItems<ClipShowEntity>?,
    listState: LazyListState,
): ClipListAdPresentation {
    /** 与共享列表相同的已加载快照；历史态没有分页来源，不读取保留的旧结果。 */
    val snapshot = pagedClips?.itemSnapshotList
    /** 复用去重与源索引映射，确保广告内容判断和实际 Lazy 渲染没有两套口径。 */
    val renderEntries = remember(snapshot) {
        snapshot?.let(::buildUniqueClipPagingRenderEntries).orEmpty()
    }
    /** 视口只保存布局索引/稳定 key，逐像素 offset 变化不触发整页敏感评估和搜索头重组。 */
    val visibleItems by remember(listState) {
        derivedStateOf {
            // 不保留正文或尺寸，append 状态等非业务 key 后续会被源索引匹配排除。
            listState.layoutInfo.visibleItemsInfo.map { item -> item.index to item.key }
        }
    }
    /** 既有详情敏感策略只在本地使用，匹配正文不会进入广告请求或日志。 */
    val sensitivityPolicy = remember { DetailAdSensitivityPolicy() }
    /** 正文资格只在当前快照或可见身份改变时重算，不因输入栏尺寸/主题等无关重组重复匹配。 */
    val eligible = remember(snapshot, visibleItems) {
        /** 仅按可见 item 的渲染索引 peek 已加载正文，避免扫描整个快照正文或触发预取。 */
        val visibleClips = visibleItems.mapNotNull { item ->
            /** Pair 的 first 是布局索引、second 是稳定 key；旧布局不能借用新代际正文。 */
            val entry = renderEntries.getOrNull(item.first)?.takeIf { it.clipId == item.second }
            entry?.let { pagedClips?.peek(it.sourceIndex) }
        }
        /** 当前代际首次布局前用首条保护同帧呈现；旧业务 key 尚未重测也按未布局处理，纯页尾不扩展范围。 */
        val contextClips = if (visibleItems.isEmpty() ||
            (visibleClips.isEmpty() && visibleItems.any { item -> item.second is Long })
        ) {
            listOfNotNull(renderEntries.firstOrNull()?.let { pagedClips?.peek(it.sourceIndex) })
        } else {
            visibleClips
        }
        // 判定结果是唯一向广告宿主传递的值，不输出命中片段或用户内容。
        contextClips.none { clip -> sensitivityPolicy.shouldHideAds(clip.content) }
    }
    return ClipListAdPresentation(
        renderEntries = renderEntries,
        contentState = ListAdContentState.from(
            hasRenderedItems = renderEntries.isNotEmpty(),
            // 只有列表实际显示首次加载画面时才标为 Loading；保留旧记录的 refresh 仍渲染真实内容。
            showsLoading = renderEntries.isEmpty() && pagedClips?.loadState?.refresh is LoadState.Loading,
        ),
        eligible = eligible,
    )
}
