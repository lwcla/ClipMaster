package com.cla.clip.master.ui.widget.image

import org.junit.Assert.*
import org.junit.Test

/** 共享缩略图事件必须与请求挂载一致，取消不产生失败。 */
class ImageThumbnailLoadSessionTest {
    /** Lazy 子组合重激活必须创建新尝试，不能永久停留在关闭状态。 */
    @Test fun reopenCreatesFreshAttemptAfterDisposal() {
        // 收集关闭前后完整事件序列。
        val events = mutableListOf<ImageThumbnailLoadEvent>()
        // 同一个门闩模拟可复用组合离场后再次挂载。
        val session = ImageThumbnailLoadSession(events::add)
        session.loading()
        session.complete(ImageThumbnailLoadPhase.Failed)
        session.close()
        session.complete(ImageThumbnailLoadPhase.Succeeded)
        assertEquals(2, events.size)
        session.open()
        session.complete(ImageThumbnailLoadPhase.Succeeded)
        assertEquals(4, events.size)
        assertNotSame(events[0].attempt, events[2].attempt)
        assertEquals(ImageThumbnailLoadPhase.Succeeded, events.last().phase)
    }

    /** 加载终态只属于当次尝试，新尝试身份与前一次不同。 */
    @Test fun loadingAndTerminalShareAttemptAndRetryGetsNewIdentity() {
        // 收集实际门闩发出的事件，不模拟被测状态机。
        val events = mutableListOf<ImageThumbnailLoadEvent>()
        // 被测门闩只发布加载事件，不持有图片数据。
        val session = ImageThumbnailLoadSession(events::add)
        session.loading()
        session.complete(ImageThumbnailLoadPhase.Failed)
        session.loading()
        session.complete(ImageThumbnailLoadPhase.Succeeded)
        assertEquals(listOf(ImageThumbnailLoadPhase.Loading, ImageThumbnailLoadPhase.Failed,
            ImageThumbnailLoadPhase.Loading, ImageThumbnailLoadPhase.Succeeded), events.map { it.phase })
        assertSame(events[0].attempt, events[1].attempt)
        assertSame(events[2].attempt, events[3].attempt)
        assertNotSame(events[0].attempt, events[2].attempt)
    }

    /** 缓存命中可直接返回成功，但调用方仍需要先获得有效尝试身份。 */
    @Test fun immediateCacheSuccessStillEmitsStart() {
        // 没有显式 loading 回调也应构成完整的一次加载。
        val events = mutableListOf<ImageThumbnailLoadEvent>()
        // 被测门闩只发布加载事件，不持有图片数据。
        val session = ImageThumbnailLoadSession(events::add)
        session.complete(ImageThumbnailLoadPhase.Succeeded)
        assertEquals(2, events.size)
        assertEquals(ImageThumbnailLoadPhase.Loading, events[0].phase)
        assertEquals(ImageThumbnailLoadPhase.Succeeded, events[1].phase)
        assertSame(events[0].attempt, events[1].attempt)
    }

    /** 离场后的回调不再发布，重复终态也不重复汇总。 */
    @Test fun closingAndRepeatedTerminalDoNotPublishExtraEvents() {
        // 先记录失败，离场不能通过清空状态撤销这份有效失败证据。
        val events = mutableListOf<ImageThumbnailLoadEvent>()
        // 被测门闩只发布加载事件，不持有图片数据。
        val session = ImageThumbnailLoadSession(events::add)
        session.loading()
        session.complete(ImageThumbnailLoadPhase.Failed)
        session.complete(ImageThumbnailLoadPhase.Failed)
        session.close()
        session.loading()
        session.complete(ImageThumbnailLoadPhase.Succeeded)
        assertEquals(2, events.size)
        assertEquals(ImageThumbnailLoadPhase.Failed, events.last().phase)
    }
}
