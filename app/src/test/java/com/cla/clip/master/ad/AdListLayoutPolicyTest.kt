package com.cla.clip.master.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定广告继续沿用业务交互保护，旧混排密度规则由新的固定空间/呈现测试替代。 */
class AdListLayoutPolicyTest {
    /** 滑动、业务交互或置顶纠偏中都不得改变广告布局。 */
    @Test fun layoutMutationWaitsForEveryInteractionToFinish() {
        assertTrue(AdListLayoutPolicy.canMutateLayout(false, false, false))
        assertFalse(AdListLayoutPolicy.canMutateLayout(true, false, false))
        assertFalse(AdListLayoutPolicy.canMutateLayout(false, true, false))
        assertFalse(AdListLayoutPolicy.canMutateLayout(false, false, true))
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
