package com.cla.clip.master.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实内容计数、网格行尾和无限广告位的回归保护；不依赖广告 SDK。 */
class ListAdPlacementPolicyTest {
    /** 空数据不生成广告，单列首条之后及每二十条之后生成广告。 */
    @Test fun singleColumnUsesRealContentPositionsWithoutMinimumCount() {
        assertEquals(emptyList<Int>(), ListAdPlacementPolicy.anchorIndices(0))
        assertEquals(listOf(0), ListAdPlacementPolicy.anchorIndices(1))
        assertEquals(listOf(0), ListAdPlacementPolicy.anchorIndices(20))
        assertEquals(listOf(0, 20, 40), ListAdPlacementPolicy.anchorIndices(41))
    }

    /** 惰性列表可直接查询当前内容是否有尾槽，避免每个 item 重新生成整张广告位置表。 */
    @Test fun singleColumnSlotLookupMatchesPlacementPolicy() {
        assertNull(ListAdPlacementPolicy.singleColumnSlotIndex(-1))
        assertEquals(0, ListAdPlacementPolicy.singleColumnSlotIndex(0))
        assertNull(ListAdPlacementPolicy.singleColumnSlotIndex(19))
        assertEquals(1, ListAdPlacementPolicy.singleColumnSlotIndex(20))
        assertEquals(2500, ListAdPlacementPolicy.singleColumnSlotIndex(50000))
    }

    /** 敏感锚点跳过后仍使用真实内容索引，后续广告不提前补位或压缩序号。 */
    @Test fun sensitiveAnchorDoesNotMoveLaterSlots() {
        assertNull(ListAdPlacementPolicy.singleColumnSlotIndex(0, eligible = false))
        assertEquals(1, ListAdPlacementPolicy.singleColumnSlotIndex(20, eligible = true))
        assertEquals(2, ListAdPlacementPolicy.singleColumnSlotIndex(40, eligible = true))
    }

    /** 首广告准备不依赖卡片高度；其余广告必须有真实内容与视口相交，不能由预取触发。 */
    @Test fun laterSlotsWaitUntilTheirActualContentIsVisible() {
        assertTrue(ListAdPlacementPolicy.shouldRequestSlot(0, null, null, 0, 100))
        assertFalse(ListAdPlacementPolicy.shouldRequestSlot(1, null, null, 0, 100))
        assertTrue(ListAdPlacementPolicy.shouldRequestSlot(1, 10, 20, 0, 100))
        assertFalse(ListAdPlacementPolicy.shouldRequestSlot(1, 100, 20, 0, 100))
        assertFalse(ListAdPlacementPolicy.shouldRequestSlot(1, -20, 20, 0, 100))
    }

    /** 广告对象缓存上限不能变成列表广告位置数量上限。 */
    @Test fun scrollingHasNoPlacementCountCap() {
        assertEquals(2501, ListAdPlacementPolicy.anchorIndices(50001).size)
        assertEquals(50000, ListAdPlacementPolicy.anchorIndices(50001).last())
    }

    /** 四列不足一行时也紧随已有内容，后续位置始终在完整行末。 */
    @Test fun gridAnchorsFollowCompleteFirstRowAndTwentyMoreItems() {
        assertEquals(listOf(0), ListAdPlacementPolicy.anchorIndices(1, 4))
        assertEquals(listOf(2), ListAdPlacementPolicy.anchorIndices(3, 4))
        assertEquals(listOf(3, 23, 43), ListAdPlacementPolicy.anchorIndices(44, 4))
    }

    /** 滑动、业务交互或置顶纠偏中都不得改变广告布局。 */
    @Test fun layoutMutationWaitsForEveryInteractionToFinish() {
        assertTrue(ListAdPlacementPolicy.canMutateLayout(false, false, false))
        assertFalse(ListAdPlacementPolicy.canMutateLayout(true, false, false))
        assertFalse(ListAdPlacementPolicy.canMutateLayout(false, true, false))
        assertFalse(ListAdPlacementPolicy.canMutateLayout(false, false, true))
    }
}

/** 稳定内容锚点在插入、删除和原锚点消失时的回归保护。 */
class AdListAnchorPolicyTest {
    /** 插入其他内容后仍恢复同一条业务内容及原像素偏移。 */
    @Test fun insertionRestoresSameContentKeyAndOffset() {
        // 原顺序只含业务 key；广告永远不成为恢复目标。
        val anchor = AdListAnchor(listOf(1L, 2L, 3L), 1, 17)
        assertEquals(AdListAnchorTarget(2, 17), AdListAnchorPolicy.resolve(anchor, listOf(0L, 1L, 2L, 3L)))
    }

    /** 原锚点被删时先取其后最近幸存内容，再取之前内容。 */
    @Test fun removedAnchorPrefersNextSurvivorThenPrevious() {
        // 锚点原为第二项；后向和前向降级共用同一份快照。
        val anchor = AdListAnchor(listOf(1L, 2L, 3L, 4L), 1, 12)
        assertEquals(AdListAnchorTarget(1, 12), AdListAnchorPolicy.resolve(anchor, listOf(1L, 4L)))
        assertEquals(AdListAnchorTarget(0, 12), AdListAnchorPolicy.resolve(anchor, listOf(1L)))
        assertNull(AdListAnchorPolicy.resolve(anchor, emptyList()))
        assertEquals(AdListAnchorTarget(0, 0), AdListAnchorPolicy.resolve(anchor, listOf(9L)))
    }
}
