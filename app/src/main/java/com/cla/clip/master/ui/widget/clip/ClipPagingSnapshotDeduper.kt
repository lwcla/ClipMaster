package com.cla.clip.master.ui.widget.clip

import androidx.paging.ItemSnapshotList
import com.cla.clip.base.general.entity.ClipShowEntity

/**
 * 共享剪贴分页列表的单条渲染索引。
 *
 * `sourceIndex` 指向 `LazyPagingItems` 当前代际中的真实位置，
 * `clipId` 作为 Compose 稳定 key 使用，避免页面层再从实体里重复拆字段。
 */
internal data class ClipPagingRenderEntry(
    /** 当前分页代际里该条剪贴记录对应的真实索引。 */
    val sourceIndex: Int,

    /** 当前条目的稳定业务主键；共享列表只允许同一快照里渲染一次。 */
    val clipId: Long,
)

/**
 * 基于当前 Paging 快照生成唯一渲染索引。
 *
 * Room + Paging 在数据重排、失效切代或旧页回收边界上，可能短暂把同一条 `clip.id`
 * 同时暴露两次；Compose `LazyColumn` 一旦收到重复 key 就会直接抛异常。
 * 这里按当前已加载快照保留首个出现的 id，并把它映射回 `LazyPagingItems` 的真实索引，
 * 让共享列表在下一次稳定快照到来前先安全渲染。
 */
internal fun buildUniqueClipPagingRenderEntries(
    snapshot: ItemSnapshotList<ClipShowEntity>,
): List<ClipPagingRenderEntry> {
    /** 当前快照里已加载的剪贴记录；未加载占位不会出现在这里。 */
    val loadedItems = snapshot.items
    /** 已加载数据在整条 Paging 列表中的起始索引；需要加回去才能继续使用 Paging 原索引访问。 */
    val firstLoadedSourceIndex = snapshot.placeholdersBefore
    /** 已经输出过的剪贴主键；重复 id 只保留第一次出现的位置，降低切代瞬间的视觉跳动。 */
    val seenClipIds = LinkedHashSet<Long>(loadedItems.size)
    /** 最终交给 LazyColumn 的渲染索引列表。 */
    val uniqueEntries = ArrayList<ClipPagingRenderEntry>(loadedItems.size)

    loadedItems.forEachIndexed { loadedOffset, clip ->
        if (seenClipIds.add(clip.id)) {
            uniqueEntries += ClipPagingRenderEntry(
                sourceIndex = firstLoadedSourceIndex + loadedOffset,
                clipId = clip.id,
            )
        }
    }
    return uniqueEntries
}
