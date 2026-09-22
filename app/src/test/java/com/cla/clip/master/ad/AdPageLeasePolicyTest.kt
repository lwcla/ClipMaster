package com.cla.clip.master.ad

import org.junit.Assert.*
import org.junit.Test

/** 保护每页驻留上限、可见广告与一次请求规则，不把缓存上限误当累计广告上限。 */
class AdPageLeasePolicyTest {
    /** 尚未进入视口的广告默认离屏，第四个位置仍可淘汰最早离屏位置。 */
    @Test fun newPositionsContinueAfterThreeResidentAds() {
        // 独立页面的内存账本，不涉及 SDK 或真实用户内容。
        val policy = AdPageLeasePolicy()
        assertTrue(policy.acquire("a"))
        assertTrue(policy.acquire("b"))
        assertTrue(policy.acquire("c"))
        assertTrue(policy.acquire("d"))
        assertEquals(setOf("b", "c", "d"), policy.residentKeys)
        assertFalse(policy.acquire("a"))
        assertTrue(policy.acquire("e"))
    }

    /** 淘汰依据最后一次离开视口的顺序，不依据最早创建顺序。 */
    @Test fun evictionUsesOldestOffscreenTransitionInsteadOfAcquisitionOrder() {
        // 创建顺序 a/b/c；离屏顺序 b/a/c，用于揭示 FIFO 的错误淘汰。
        val policy = AdPageLeasePolicy()
        listOf("a", "b", "c").forEach { key ->
            policy.acquire(key)
            policy.updateVisibility(key, true)
        }
        policy.updateVisibility("b", false)
        policy.updateVisibility("a", false)
        policy.updateVisibility("c", false)
        assertTrue(policy.acquire("d"))
        assertEquals(setOf("a", "c", "d"), policy.residentKeys)
    }

    /** 全部广告可见时不淘汰其中任意一个，也不消耗新位置的请求机会。 */
    @Test fun fullVisibleBudgetDefersRequestWithoutConsumingAttempt() {
        // 容量缩小为一，精确检查可见保护和后续重试许可。
        val policy = AdPageLeasePolicy(capacity = 1)
        assertTrue(policy.acquire("a"))
        assertTrue(policy.updateVisibility("a", true))
        assertFalse(policy.acquire("b"))
        assertEquals(setOf("a"), policy.residentKeys)
        assertTrue(policy.updateVisibility("a", false))
        assertTrue(policy.acquire("b"))
        assertEquals(setOf("b"), policy.residentKeys)
        assertFalse(policy.acquire("a"))
    }

    /** 重复上报离屏不能刷新年龄，未知 key 不应创建驻留状态。 */
    @Test fun duplicateVisibilityDoesNotRefreshEvictionAge() {
        // a 从申请起离屏；重复 false 必须保留它最早的离屏序号。
        val policy = AdPageLeasePolicy(capacity = 2)
        policy.acquire("a")
        policy.acquire("b")
        assertFalse(policy.updateVisibility("a", false))
        assertFalse(policy.updateVisibility("missing", true))
        assertTrue(policy.acquire("c"))
        assertEquals(setOf("b", "c"), policy.residentKeys)
    }

    /** 可见性方法只在真实状态变化时报告 changed，避免宿主形成重试重组循环。 */
    @Test fun visibilityChangesReportOnlyActualTransitions() {
        // 新租约尚未展示，因此第一次 false 不是变化。
        val policy = AdPageLeasePolicy()
        policy.acquire("a")
        assertFalse(policy.updateVisibility("a", false))
        assertTrue(policy.updateVisibility("a", true))
        assertFalse(policy.updateVisibility("a", true))
        assertTrue(policy.updateVisibility("a", false))
        assertFalse(policy.updateVisibility("a", false))
        policy.release("a")
        assertFalse(policy.updateVisibility("a", true))
    }

    /** 释放、失败或迟到后不得重复加载同一会话位置。 */
    @Test fun releasedPositionCannotBeRequestedAgain() {
        // 每个业务阶段使用单独账本。
        val policy = AdPageLeasePolicy()
        assertTrue(policy.acquire("slot"))
        policy.release("slot")
        assertFalse(policy.acquire("slot"))
        assertTrue(policy.residentKeys.isEmpty())
    }

    /** 隐藏全部位置只释放资源，不解除重复请求保护。 */
    @Test fun clearingResidentAdsPreservesAttempts() {
        // 页面关闭隐私权限后不能意外重新请求。
        val policy = AdPageLeasePolicy()
        policy.acquire("slot")
        policy.clear()
        assertFalse(policy.acquire("slot"))
    }
}
