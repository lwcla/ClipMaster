package com.cla.clip.feature.ad.csj

import com.cla.clip.feature.ad.api.AdSlotEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 穿山甲广告事件去重测试，保证展示、点击和释放不会重复污染指标。 */
class CsjAdEventDeduplicatorTest {
    /** 展示事件同一请求内只允许上报一次。 */
    @Test
    fun impressionIsDeduplicated() {
        /** 当前去重器；代表一个 requestNonce 的事件生命周期。 */
        val deduplicator = CsjAdEventDeduplicator()

        assertTrue(deduplicator.shouldEmit(AdSlotEventType.Impression))
        assertFalse(deduplicator.shouldEmit(AdSlotEventType.Impression))
    }

    /** 点击事件同一请求内只允许上报一次。 */
    @Test
    fun clickedIsDeduplicated() {
        /** 当前去重器；点击重复回调应被过滤。 */
        val deduplicator = CsjAdEventDeduplicator()

        assertTrue(deduplicator.shouldEmit(AdSlotEventType.Clicked))
        assertFalse(deduplicator.shouldEmit(AdSlotEventType.Clicked))
    }

    /** 释放事件同一请求内只允许上报一次。 */
    @Test
    fun releasedIsDeduplicated() {
        /** 当前去重器；多路径释放只能产生一次 Released。 */
        val deduplicator = CsjAdEventDeduplicator()

        assertTrue(deduplicator.shouldEmit(AdSlotEventType.Released))
        assertFalse(deduplicator.shouldEmit(AdSlotEventType.Released))
    }

    /** 请求和失败类事件不去重，便于保留真实状态路径。 */
    @Test
    fun nonInteractionEventsAreNotDeduplicated() {
        /** 当前去重器；LoadFailed 允许不同原因路径各自上报。 */
        val deduplicator = CsjAdEventDeduplicator()

        assertTrue(deduplicator.shouldEmit(AdSlotEventType.LoadFailed))
        assertTrue(deduplicator.shouldEmit(AdSlotEventType.LoadFailed))
    }
}
