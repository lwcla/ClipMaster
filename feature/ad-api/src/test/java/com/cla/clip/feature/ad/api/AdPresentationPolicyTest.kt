package com.cla.clip.feature.ad.api

import org.junit.Assert.*
import org.junit.Test

/** 保护持续内容资格与首次挂载安全门，滚动保护不能延迟空态撤下。 */
class AdPresentationPolicyTest {
    /** 已呈现的广告也必须在空态当帧撤下，不受滚动或按压许可影响。 */
    @Test fun contentLossHidesPresentedViewRegardlessOfLayoutGate() {
        assertFalse(AdPresentationPolicy.canMount(true, false, true, false))
        assertFalse(AdPresentationPolicy.canMount(true, false, true, true))
    }

    /** 自动隐藏后的缓存 View 必须重新经过布局安全门，素材未到不能展示控件。 */
    @Test fun cachedViewRemountWaitsForLayoutSafety() {
        assertFalse(AdPresentationPolicy.canMount(true, true, false, false))
        assertTrue(AdPresentationPolicy.canMount(true, true, false, true))
        assertTrue(AdPresentationPolicy.canMount(true, true, true, false))
        assertFalse(AdPresentationPolicy.canMount(false, true, false, true))
    }
}
