package com.cla.clip.master.ui.page.image

import com.cla.clip.master.ui.widget.image.ImageThumbnailLoadEvent
import com.cla.clip.master.ui.widget.image.ImageThumbnailLoadPhase
import org.junit.Assert.*
import org.junit.Test

/** 合成请求身份验证失败筛选边界，不联网、不读用户内容。 */
class ImageCandidateLoadTrackerTest {
    /** 未加载/加载中不算失败，成功清除失败而不恢复用户已经取消的选择。 */
    @Test fun failureAndRecoveryDoNotOverrideSelection() {
        // 独立规则对象对应同一提取会话；请求版本只是合成字符串。
        val tracker = ImageCandidateLoadTracker()
        // 使用生产选择对象验证成功不会自动恢复用户取消。
        val selection = ImageCandidateSelection()
        // 合成请求版本不含真实 URL 或凭据。
        val version = listOf("synthetic-image")
        // 一次请求的内存身份，开始与终态共享此对象。
        val attempt = Any()
        tracker.update(1, mapOf("a" to version, "b" to version))
        selection.update(listOf("a", "b"))
        assertTrue(tracker.failedKeys.isEmpty())
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Loading))
        assertTrue(tracker.failedKeys.isEmpty())
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Failed))
        assertEquals(setOf("a"), tracker.failedKeys.toSet())
        assertEquals(1, selection.deselect(tracker.failedKeys.toSet()))
        // 新加载尝试使失败转为加载中，最终成功不能恢复业务选择。
        val retry = Any()
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(retry, ImageThumbnailLoadPhase.Loading))
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(retry, ImageThumbnailLoadPhase.Succeeded))
        assertTrue(tracker.failedKeys.isEmpty())
        assertEquals(setOf("b"), selection.selectedKeys.toSet())
    }

    /** 同版本旧成功不能覆盖新失败，且没有有效开始的终态不能写入。 */
    @Test fun onlyLatestAttemptMayReportTerminalState() {
        // 两个尝试使用完全相同的版本，以便单独验证尝试身份。
        val tracker = ImageCandidateLoadTracker()
        // 两次尝试版本相同，仅尝试身份不同。
        val version = listOf("same")
        // 旧尝试用于模拟延迟终态。
        val old = Any()
        // 最新尝试拥有写入资格。
        val current = Any()
        tracker.update(1, mapOf("a" to version))
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(old, ImageThumbnailLoadPhase.Failed))
        assertTrue(tracker.failedKeys.isEmpty())
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(old, ImageThumbnailLoadPhase.Loading))
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(current, ImageThumbnailLoadPhase.Loading))
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(current, ImageThumbnailLoadPhase.Failed))
        tracker.onLoadEvent(1, "a", version, ImageThumbnailLoadEvent(old, ImageThumbnailLoadPhase.Succeeded))
        assertEquals(setOf("a"), tracker.failedKeys.toSet())
    }

    /** 列表重排保留结果；请求版本或提取轮次改变后拒绝迟到回调。 */
    @Test fun snapshotVersionAndExtractionBoundariesInvalidateOldResults() {
        // 旧版本和新版本共用候选 key，模拟原图地址或请求头升级。
        val tracker = ImageCandidateLoadTracker()
        // 旧版本使用合成请求头标记。
        val oldVersion = listOf("old", "header-a")
        // 仅头信息变化也必须清除旧失败。
        val newVersion = listOf("old", "header-b")
        // 一次请求的内存身份，开始与终态共享此对象。
        val attempt = Any()
        tracker.update(1, mapOf("a" to oldVersion, "b" to oldVersion))
        tracker.onLoadEvent(1, "a", oldVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Loading))
        tracker.onLoadEvent(1, "a", oldVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Failed))
        tracker.update(1, linkedMapOf("b" to oldVersion, "a" to oldVersion))
        assertEquals(setOf("a"), tracker.failedKeys.toSet())
        tracker.update(1, mapOf("a" to newVersion))
        tracker.onLoadEvent(1, "a", oldVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Failed))
        assertTrue(tracker.failedKeys.isEmpty())
        tracker.onLoadEvent(1, "a", newVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Loading))
        tracker.update(2, mapOf("a" to newVersion))
        tracker.onLoadEvent(1, "a", newVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Failed))
        assertTrue(tracker.failedKeys.isEmpty())
        tracker.update(2, emptyMap())
        tracker.onLoadEvent(2, "a", newVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Loading))
        tracker.onLoadEvent(2, "a", newVersion, ImageThumbnailLoadEvent(attempt, ImageThumbnailLoadPhase.Failed))
        assertTrue(tracker.failedKeys.isEmpty())
    }
}
