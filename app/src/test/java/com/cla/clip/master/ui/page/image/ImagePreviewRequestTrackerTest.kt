package com.cla.clip.master.ui.page.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保护快速切图、URL 升级及重新提取时旧请求的写回边界。 */
class ImagePreviewRequestTrackerTest {
    /** 同图新请求开始后，旧成功回调和旧 finally 都失去写权限。 */
    @Test fun newerRequestRejectsOldCompletion() {
        // 只使用合成版本字符串，不使用真实地址或凭证。
        val tracker = ImagePreviewRequestTracker()
        // 旧资源请求先开始，随后被同身份的新资源替换。
        val first = tracker.begin("a", listOf("v1"))
        // 新版本独占该候选的写回资格。
        val second = tracker.begin("a", listOf("v2"))
        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))
        assertFalse(tracker.hasVersion("a", listOf("v1")))
        assertTrue(tracker.hasVersion("a", listOf("v2")))
    }

    /** 不同图请求独立；清空会话后所有旧票据失效，同版本再打开也不会复活。 */
    @Test fun clearInvalidatesTicketsAcrossSessions() {
        // 票据身份独立于版本文本，相同版本跨会话仍不能写回。
        val tracker = ImagePreviewRequestTracker()
        // 第一张图片的初始任务票据。
        val first = tracker.begin("a", listOf("v1"))
        // 另一张图片独立持有写回资格。
        val other = tracker.begin("b", listOf("v1"))
        assertTrue(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(other))
        tracker.clear()
        tracker.begin("a", listOf("v1"))
        assertFalse(tracker.isCurrent(first))
        assertFalse(tracker.isCurrent(other))
    }
}
