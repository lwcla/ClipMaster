package com.cla.clip.feature.ad.csj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 穿山甲释放保护测试，覆盖多路径重复 release 的幂等性。 */
class CsjAdReleaseGuardTest {
    /** 首次释放应执行动作并标记已释放。 */
    @Test
    fun firstReleaseRunsAction() {
        /** 当前释放保护器；代表一个广告请求生命周期。 */
        val releaseGuard = CsjAdReleaseGuard()
        /** 释放动作执行次数；用于确认幂等边界。 */
        var releaseCount = 0

        /** 当前释放结果；首次调用应执行 onRelease。 */
        val released = releaseGuard.releaseOnce { releaseCount += 1 }

        assertTrue(released)
        assertTrue(releaseGuard.isReleased())
        assertEquals(1, releaseCount)
    }

    /** 重复释放不应再次执行动作。 */
    @Test
    fun repeatedReleaseDoesNotRunActionAgain() {
        /** 当前释放保护器；模拟页面离开和超时同时到达。 */
        val releaseGuard = CsjAdReleaseGuard()
        /** 释放动作执行次数；重复调用后仍应为一。 */
        var releaseCount = 0

        releaseGuard.releaseOnce { releaseCount += 1 }
        /** 当前重复释放结果；已经释放后应返回 false。 */
        val releasedAgain = releaseGuard.releaseOnce { releaseCount += 1 }

        assertFalse(releasedAgain)
        assertTrue(releaseGuard.isReleased())
        assertEquals(1, releaseCount)
    }
}
