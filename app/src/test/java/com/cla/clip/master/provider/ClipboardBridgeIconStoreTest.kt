package com.cla.clip.master.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory

/** Provider 图标路径解析测试，保护 content write 只能写入事件图标路径。 */
class ClipboardBridgeIconStoreTest {
    /** 被测图标临时存储管理器。 */
    private val iconStore = ClipboardBridgeIconStore()

    @Test
    /** 标准 icon/<eventId> 路径应解析出 eventId。 */
    fun parseEventIdAcceptsIconPath() {
        /** Provider URI 路径段。 */
        val segments = listOf("icon", "event-123")

        assertEquals("event-123", iconStore.parseEventId(segments))
    }

    @Test
    /** 非 icon 路径必须拒绝，避免 Provider 开放额外文件写入口。 */
    fun parseEventIdRejectsUnsupportedPath() {
        /** 非法 Provider URI 路径段。 */
        val segments = listOf("other", "event-123")

        assertThrows(FileNotFoundException::class.java) {
            iconStore.parseEventId(segments)
        }
    }

    @Test
    /** eventId 包含路径字符时必须拒绝，避免写出临时目录。 */
    fun parseEventIdRejectsUnsafeEventId() {
        /** 带路径穿越意图的 Provider URI 路径段。 */
        val segments = listOf("icon", "../bad")

        assertThrows(FileNotFoundException::class.java) {
            iconStore.parseEventId(segments)
        }
    }

    @Test
    /** 过期临时图标会被清理，未过期文件和目录必须保留。 */
    fun cleanupExpiredFilesDeletesOnlyExpiredFiles() {
        /** 本次测试使用的临时目录。 */
        val dir = createTempDirectory(prefix = "clipboard_bridge_icons_test").toFile()

        try {
            /** 固定当前时间，避免测试依赖真实系统时间。 */
            val now = 1_000_000L
            /** 超过 10 分钟 TTL 的旧正式图标文件。 */
            val expiredIconFile = File(dir, "old.png").apply {
                writeText("old")
                setLastModified(now - 11 * 60 * 1000L)
            }
            /** 超过 10 分钟 TTL 的旧半成品图标文件。 */
            val expiredTempFile = File(dir, "old.tmp").apply {
                writeText("old-temp")
                setLastModified(now - 11 * 60 * 1000L)
            }
            /** 未超过 TTL 的新图标文件。 */
            val freshFile = File(dir, "fresh.png").apply {
                writeText("fresh")
                setLastModified(now - 60 * 1000L)
            }
            /** 非图标扩展名文件不属于 Provider 临时图标，清理时必须跳过。 */
            val unrelatedFile = File(dir, "notes.txt").apply {
                writeText("keep")
                setLastModified(now - 11 * 60 * 1000L)
            }
            /** 目录项不属于临时图标文件，清理时必须跳过。 */
            val childDir = File(dir, "nested").apply {
                mkdirs()
                setLastModified(now - 11 * 60 * 1000L)
            }

            assertEquals(2, iconStore.cleanupExpiredFiles(dir, now))
            assertFalse(expiredIconFile.exists())
            assertFalse(expiredTempFile.exists())
            assertTrue(freshFile.exists())
            assertTrue(unrelatedFile.exists())
            assertTrue(childDir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    /** hash 缺失时允许保存，hash 不匹配时必须降级为占位图。 */
    fun isHashAcceptedAllowsBlankAndRejectsMismatch() {
        assertTrue(iconStore.isHashAccepted(null, "actual"))
        assertTrue(iconStore.isHashAccepted("", "actual"))
        assertTrue(iconStore.isHashAccepted("actual", "actual"))
        assertFalse(iconStore.isHashAccepted("expected", "actual"))
    }
}
