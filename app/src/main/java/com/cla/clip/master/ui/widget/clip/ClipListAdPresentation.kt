package com.cla.clip.master.ui.widget.clip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.ui.widget.ad.ListAdContentState

/**
 * 剪贴列表与固定广告共同消费的当前展示快照，只在编译期调用，不是外部协议。
 * 渲染索引保留 Paging 的真实位置；广告宿主只接收内容三态，不接收这些索引或正文。
 */
internal data class ClipListAdPresentation(
    /** 去重后的真实业务条目，placeholder 不会进入该集合。 */
    val renderEntries: List<ClipPagingRenderEntry>,
    /** 当前画面有真实记录、仅加载画面或没有内容，不保存上次非空状态。 */
    val contentState: ListAdContentState,
)

/**
 * 从共享 Paging 快照同步读取内容三态，不观察滚动视口、不读取正文或创建额外查询。
 * 搜索历史态传入空来源，立即表达无结果内容，但外层广告请求宿主仍可保持组合。
 */
@Composable
internal fun rememberClipListAdPresentation(
    pagedClips: LazyPagingItems<ClipShowEntity>?,
): ClipListAdPresentation {
    /** 与共享列表相同的已加载快照；历史态没有分页来源，不读取保留的旧结果。 */
    val snapshot = pagedClips?.itemSnapshotList
    /** 复用去重与源索引映射，确保广告内容判断和实际 Lazy 渲染没有两套口径。 */
    val renderEntries = remember(snapshot) {
        snapshot?.let(::buildUniqueClipPagingRenderEntries).orEmpty()
    }
    return ClipListAdPresentation(
        renderEntries = renderEntries,
        contentState = ListAdContentState.from(
            hasRenderedItems = renderEntries.isNotEmpty(),
            // 只有列表实际显示首次加载画面时才标为 Loading；保留旧记录的 refresh 仍渲染真实内容。
            showsLoading = renderEntries.isEmpty() && pagedClips?.loadState?.refresh is LoadState.Loading,
        ),
    )
}
