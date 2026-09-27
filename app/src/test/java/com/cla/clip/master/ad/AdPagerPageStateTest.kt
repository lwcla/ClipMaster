package com.cla.clip.master.ad

import org.junit.Assert.*
import org.junit.Test

/** Pager 手势不能冒充页面生命周期，防止斜滑、回弹刷新广告。 */
class AdPagerPageStateTest {
    /** 已选中页面在拖动中仍属于本次访问，只暂停新广告展开。 */
    @Test fun draggingKeepsSelectedVisitButBlocksNewLayout() {
        /** 模拟手势中尚未提交切页的首页。 */
        val state = AdPagerPageState(page = 0, settledPage = 0, scrolling = true)
        assertTrue(state.visibleToUser)
        assertTrue(state.layoutBlocked)
        assertFalse(AdPagerPageState(1, 0, true).visibleToUser)
    }

    /** 反复拖动回到原页必须保留已获取的广告，不能消耗新请求身份。 */
    @Test fun cancelledGesturesKeepTheSameSessionAndLease() {
        /** 可见首页初次访问的资源所有者。 */
        val owner = PageAdSessionOwner(true, true)
        /** 已有素材的原会话，作为手势前后身份比较基准。 */
        val original = owner.session
        original.acquire("slot")
        // scrolling 是一次手势起止，不代表用户切换了页面。
        for (scrolling in listOf(true, false, true, false)) {
            owner.update(AdPagerPageState(0, 0, scrolling).visibleToUser, true)
        }
        assertSame(original, owner.session)
        assertEquals(setOf("slot"), owner.session.residentKeys)
    }

    /** 真正切到其他 Tab 才释放，返回后应允许原位置重新请求。 */
    @Test fun settledTabChangeReleasesAndReturnStartsFreshVisit() {
        /** 模拟先在首页显示、再切换到我的页的访问。 */
        val owner = PageAdSessionOwner(true, true)
        /** 离开时应立即失活的旧对象。 */
        val original = owner.session
        original.acquire("slot")
        owner.update(AdPagerPageState(0, 1, false).visibleToUser, true)
        assertFalse(original.active)
        assertTrue(original.residentKeys.isEmpty())
        owner.update(AdPagerPageState(0, 0, false).visibleToUser, true)
        assertNotSame(original, owner.session)
        assertTrue(owner.session.acquire("slot"))
        assertFalse(AdPagerPageState(0, 0, false).layoutBlocked)
    }
}
