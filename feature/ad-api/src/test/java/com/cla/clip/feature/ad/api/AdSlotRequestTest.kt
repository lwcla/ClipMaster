package com.cla.clip.feature.ad.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实时展示门必须在同一请求内读取最新交互状态。 */
class AdSlotRequestTest {
    /** 未有业务内容仅禁止呈现，不能阻止独立广告请求。 */
    @Test fun missingContentDoesNotBlockRequestAndLiveGateReadsLatestContent() {
        // 同一请求可先缓存素材，再随真实内容出现获得呈现资格。
        var containsContent = false
        val request = AdSlotRequest(AdPlacement.HomeFeedNative, "parallel", true,
            contentPresentable = false, contentPresentableNow = { containsContent })
        assertTrue(request.canRequest)
        assertFalse(request.contentPresentableNow())
        containsContent = true
        assertTrue(request.contentPresentableNow())
    }
    /** 未提供实时门时沿用构造请求时的展示许可。 */
    @Test
    fun defaultLiveGateFollowsRevealPermission() {
        /** 默认许可保持旧调用方行为。 */
        val allowed = AdSlotRequest(AdPlacement.DetailNative, "allowed", true)
        /** 显式禁止发布素材时，默认实时门也关闭。 */
        val blocked = AdSlotRequest(AdPlacement.DetailNative, "blocked", true, canReveal = false)
        assertTrue(allowed.canRevealNow())
        assertFalse(blocked.canRevealNow())
    }

    /** 指针状态可先于重组改变，不能依赖请求对象或布尔快照重建。 */
    @Test
    fun liveGateReadsInteractionChangeWithoutRebuildingRequest() {
        /** 模拟在同一 UI 帧内开始的指针交互。 */
        var pointerPressed = false
        /** 请求快照允许展示，实时门直接读取当前交互状态。 */
        val request = AdSlotRequest(
            placement = AdPlacement.HomeFeedNative,
            requestNonce = "same-request",
            isDebugRequest = true,
            canRevealNow = { !pointerPressed },
        )
        assertTrue(request.canRevealNow())
        pointerPressed = true
        assertTrue(request.canReveal)
        assertFalse(request.canRevealNow())
        pointerPressed = false
        assertTrue(request.canRevealNow())
    }
}
