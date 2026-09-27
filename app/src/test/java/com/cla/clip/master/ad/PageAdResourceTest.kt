package com.cla.clip.master.ad

import com.cla.clip.feature.ad.api.AdReleaseReason
import com.cla.clip.feature.ad.api.AdSlotResource
import org.junit.Assert.*
import org.junit.Test

/** 验证有限缓存、回滚复用与迟到回调，不加载任何 SDK 或网络。 */
class PageAdResourceTest {
    /** 同一缓存跨组合回绑时，旧解绑/坐标回调不能把新可见广告变成淘汰候选。 */
    @Test fun staleBindingCannotMakeReattachedAdEvictable() {
        // 同一个广告资源先后绑定两个 Lazy 组合。
        val session = PageAdSession(true)
        val slot = session.acquireSlot("a", "admob")!!
        val previousBinding = Any()
        val currentBinding = Any()
        session.bindSlot("a", slot, previousBinding)
        session.bindSlot("a", slot, currentBinding)
        session.updateSlotVisibility("a", slot, currentBinding, true)
        session.detachSlot("a", slot, previousBinding)
        session.updateSlotVisibility("a", slot, previousBinding, false)
        session.acquireSlot("b", "admob")
        session.acquireSlot("c", "admob")
        session.acquireSlot("d", "admob")
        assertEquals(setOf("a", "c", "d"), session.residentKeys)
    }

    /** 暂停与普通遮挡都能绑定已有缓存，但不能发起新的资源申请。 */
    @Test fun pausedOrSuppressedPageCanOnlyReuseExistingResources() {
        // 已加载广告属于本次访问，遮挡不改变它的身份。
        val session = PageAdSession(true)
        val cached = session.acquireSlot("cached", "admob")!!
        session.canRequest = false
        assertSame(cached, session.acquireSlot("cached", "admob"))
        assertNull(session.acquireSlot("paused", "admob"))
        session.canRequest = true
        session.suppressed = true
        assertSame(cached, session.acquireSlot("cached", "admob"))
        assertNull(session.acquireSlot("covered", "admob"))
    }

    /** 离屏只更新可见性，回滚继续绑定同一个资源所有者。 */
    @Test fun detachedSlotReusesResourceUntilPageLeaves() {
        // 当前页面的缓存和释放次数。
        val session = PageAdSession(true)
        val lease = session.acquireSlot("a", "admob")!!
        var closes = 0
        lease.resources.getOrCreate { AdSlotResource { closes++ } }
        session.updateVisibility("a", true)
        session.detachSlot("a", lease)
        assertSame(lease, session.acquireSlot("a", "admob"))
        assertEquals(0, closes)
        session.clear(AdReleaseReason.PageLeft)
        session.clear(AdReleaseReason.Destroyed)
        assertEquals(1, closes)
    }

    /** 第四个位置淘汰最早离屏资源，再次进入该位置允许新请求。 */
    @Test fun evictionAllowsNewIdentityAndLateReleaseIsIgnored() {
        // a 从始至终离屏，b 真实可见而受到保护。
        val session = PageAdSession(true)
        val old = session.acquireSlot("a", "admob")!!
        var reason: AdReleaseReason? = null
        old.resources.getOrCreate { AdSlotResource { reason = it } }
        session.acquireSlot("b", "admob")
        session.updateVisibility("b", true)
        session.acquireSlot("c", "admob")
        session.acquireSlot("d", "admob")
        assertEquals(AdReleaseReason.Evicted, reason)
        assertEquals(setOf("b", "c", "d"), session.residentKeys)
        // 回滚得到新身份，旧回调不能删除新的同名租约。
        val fresh = session.acquireSlot("a", "admob")!!
        assertNotEquals(old.nonce, fresh.nonce)
        session.releaseSlot("a", old, AdReleaseReason.LoadFailed)
        assertSame(fresh, session.acquireSlot("a", "admob"))
        assertEquals(3, session.residentKeys.size)
    }

    /** 加载失败仍受同页去重限制，隐私撤销则允许重新取得许可后的请求。 */
    @Test fun failureIsTerminalButPrivacyReleaseCanBeReacquired() {
        // 两个位置分别模拟失败和授权撤销。
        val session = PageAdSession(true)
        val failed = session.acquireSlot("failed", "admob")!!
        session.releaseSlot("failed", failed, AdReleaseReason.LoadFailed)
        assertNull(session.acquireSlot("failed", "admob"))
        val revoked = session.acquireSlot("revoked", "admob")!!
        session.releaseSlot("revoked", revoked, AdReleaseReason.PrivacyRevoked)
        assertNotSame(revoked, session.acquireSlot("revoked", "admob"))
    }
}
