package com.cla.clip.master.ui.widget.ad

import org.junit.Assert.*
import org.junit.Test

/** 保护已有请求遇到键盘/分屏缩小时本访问不重试，与初始小窗口延迟首次请求分开。 */
class FixedAdSpacePolicyTest {
    /** 已持有广告时可用窗口不足最小内容视口，即使当前素材零高也必须终止本访问广告位。 */
    @Test fun shrinkingViewportTerminatesResidentSlotWithoutVisibleAd() {
        assertTrue(FixedAdSpacePolicy.mustStopSlot(80, 0, 96, hasResource = true))
        assertTrue(FixedAdSpacePolicy.mustStopSlot(200, 120, 96, hasResource = true))
    }

    /** 首次进入小窗口尚未申请，不消耗本访问机会；之后空间恢复仍可第一次请求。 */
    @Test fun initiallySmallViewportDoesNotConsumeUnrequestedSlot() {
        assertFalse(FixedAdSpacePolicy.mustStopSlot(80, 0, 96, hasResource = false))
        assertFalse(FixedAdSpacePolicy.mustStopSlot(216, 120, 96, hasResource = true))
        assertFalse(FixedAdSpacePolicy.mustStopSlot(300, 0, 96, hasResource = true))
    }
}
