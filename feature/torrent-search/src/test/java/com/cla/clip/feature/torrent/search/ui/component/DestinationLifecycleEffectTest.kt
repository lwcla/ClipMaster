package com.cla.clip.feature.torrent.search.ui.component

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目的地生命周期事件到图片展示许可的纯逻辑回归。 */
class DestinationLifecycleEffectTest {
    /** START 必须立即允许当前详情创建图片子 Composition。 */
    @Test
    fun startActivatesDestinationImmediately() {
        /** active 是消费 START 后的直接 Compose 活跃状态。 */
        val active = destinationActiveAfterEvent(currentActive = false, Lifecycle.Event.ON_START)

        assertTrue(active)
    }

    /** STOP 和 DESTROY 必须立即撤销图片展示许可。 */
    @Test
    fun stopAndDestroyDeactivateDestinationImmediately() {
        /** stopped 是消费 STOP 后的直接 Compose 活跃状态。 */
        val stopped = destinationActiveAfterEvent(currentActive = true, Lifecycle.Event.ON_STOP)
        /** destroyed 是消费 DESTROY 后的直接 Compose 活跃状态。 */
        val destroyed = destinationActiveAfterEvent(currentActive = true, Lifecycle.Event.ON_DESTROY)

        assertFalse(stopped)
        assertFalse(destroyed)
    }

    /** 与展示许可无关的事件必须保留当前状态，避免重复创建图片。 */
    @Test
    fun unrelatedEventPreservesDestinationState() {
        /** stillActive 是消费 RESUME 后保留的当前活跃状态。 */
        val stillActive = destinationActiveAfterEvent(currentActive = true, Lifecycle.Event.ON_RESUME)

        assertTrue(stillActive)
    }
}
