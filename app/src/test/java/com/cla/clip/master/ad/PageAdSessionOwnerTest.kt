package com.cla.clip.master.ad

import org.junit.Assert.*
import org.junit.Test

/** 保护页面返回重载、同页去重以及旧回调隔离，不依赖广告 SDK 或网络。 */
class PageAdSessionOwnerTest {
    /** 短暂停只阻止新增工作，恢复时仍使用原会话及原资源。 */
    @Test fun pauseThenResumePreservesResources() {
        // 模拟前台页面和已申请的广告。
        val owner = PageAdSessionOwner(true, true)
        val original = owner.session
        original.acquire("slot")
        owner.update(visible = true, enabled = true, resumed = false)
        assertTrue(original.active)
        assertFalse(original.canRequest)
        assertEquals(setOf("slot"), original.residentKeys)
        owner.update(visible = true, enabled = true, resumed = true)
        assertSame(original, owner.session)
        assertTrue(original.canRequest)
        assertFalse(original.acquire("slot"))
    }

    /** 已显示广告离开时释放，返回后同一广告位置必须有新的请求机会。 */
    @Test fun returningAfterReleaseCreatesFreshRequestBook() {
        /** 初次可见访问的会话所有者。 */
        val owner = PageAdSessionOwner(initiallyVisible = true, initiallyEnabled = true)
        /** 旧会话模拟已获得并显示素材。 */
        val previous = owner.session
        assertTrue(previous.acquire("slot"))
        owner.update(visible = false, enabled = true)
        assertFalse(previous.active)
        assertTrue(previous.residentKeys.isEmpty())
        owner.update(visible = true, enabled = true)
        assertNotSame(previous, owner.session)
        assertNotEquals(previous.nonce, owner.session.nonce)
        assertTrue(owner.session.acquire("slot"))
    }

    /** 失败不能在同一访问内自动重试，但离开返回后可以重新请求。 */
    @Test fun failedSlotRetriesOnlyOnANewVisit() {
        /** 失败的请求只消耗本次访问的机会。 */
        val owner = PageAdSessionOwner(true, true)
        owner.session.acquire("slot")
        owner.session.release("slot")
        owner.update(true, true)
        assertFalse(owner.session.acquire("slot"))
        owner.update(false, true)
        owner.update(true, true)
        assertTrue(owner.session.acquire("slot"))
    }

    /** 普通重组和业务资格恢复不能绕过请求去重或隐私限制。 */
    @Test fun eligibilityAndRepeatedVisibilityKeepTheSameVisit() {
        /** 可见但临时不满足业务资格的页面不应被当作重新进入。 */
        val owner = PageAdSessionOwner(true, true)
        /** 同一次访问始终使用同一个账本。 */
        val original = owner.session
        original.acquire("slot")
        owner.update(true, false)
        assertFalse(owner.session.active)
        owner.update(true, true)
        owner.update(true, true)
        assertSame(original, owner.session)
        assertTrue(owner.session.active)
        assertFalse(owner.session.acquire("slot"))
    }

    /** 旧 View 的迟到释放不能清除返回后新建的同名广告位。 */
    @Test fun lateOldReleaseDoesNotRemoveNewSlot() {
        /** 模拟旧新两次访问拥有同一位置名称。 */
        val owner = PageAdSessionOwner(true, true)
        /** 旧槽捕获旧会话，返回后仍只能释放旧账本。 */
        val previous = owner.session
        previous.acquire("slot")
        owner.update(false, true)
        owner.update(true, true)
        assertTrue(owner.session.acquire("slot"))
        previous.release("slot")
        assertEquals(setOf("slot"), owner.session.residentKeys)
    }

    /** 未显示和已销毁的页面均不能因生命周期迟到事件重新活跃。 */
    @Test fun hiddenAndDisposedOwnerStayInactive() {
        /** 初次组合可能发生在导航转场结束之前。 */
        val owner = PageAdSessionOwner(false, true)
        owner.update(false, true)
        assertFalse(owner.session.active)
        owner.update(true, true)
        assertTrue(owner.session.active)
        owner.session.acquire("slot")
        owner.close()
        owner.close()
        owner.update(true, true)
        assertFalse(owner.session.active)
        assertTrue(owner.session.residentKeys.isEmpty())
    }
}
