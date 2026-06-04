package com.cla.clip.feature.ad.uniad

import com.cla.clip.feature.ad.api.AdSlotEventType

/** 单个 requestNonce 内的 uni-ad 广告事件去重器，避免 SDK 重复回调污染指标。 */
internal class UniAdEventDeduplicator {
    /** 已上报过的事件类型集合；只在当前广告请求生命周期内有效。 */
    private val emittedEventTypes = mutableSetOf<AdSlotEventType>()

    /**
     * 标记事件是否允许继续上报。
     *
     * 只去重展示、点击和释放；请求、加载、失败等状态保留最新路径给调用方处理。
     */
    fun shouldEmit(eventType: AdSlotEventType): Boolean {
        if (eventType !in deduplicatedEventTypes) {
            return true
        }
        return emittedEventTypes.add(eventType)
    }

    private companion object {
        /** 需要按 requestNonce 去重的事件类型集合。 */
        private val deduplicatedEventTypes = setOf(
            AdSlotEventType.Impression,
            AdSlotEventType.Clicked,
            AdSlotEventType.Released,
        )
    }
}
