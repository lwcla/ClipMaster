package com.cla.clip.master.ad

import org.junit.Assert.assertEquals
import org.junit.Test

/** 顶部广告异步伸缩必须保持正文相对视口的位置，避免滚动正文跳动。 */
class AdScrollAnchorPolicyTest {
    /** 仍在顶部时不自动滚走新广告或正文。 */
    @Test fun staysAtTopWhenHeaderArrives() {
        assertEquals(0, AdScrollAnchorPolicy.restoreOffset(0, 0, 120))
    }
    /** 已滚入正文后广告高度增加，应补偿同样的像素量。 */
    @Test fun preservesBodyWhenHeaderGrows() {
        assertEquals(420, AdScrollAnchorPolicy.restoreOffset(300, 0, 120))
    }
    /** 广告被隐藏时扣除旧高度，滚动位置不能小于零。 */
    @Test fun preservesBodyWhenHeaderCollapses() {
        assertEquals(180, AdScrollAnchorPolicy.restoreOffset(300, 120, 0))
        assertEquals(0, AdScrollAnchorPolicy.restoreOffset(80, 120, 0))
    }
}
