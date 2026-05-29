package com.cla.clip.master.provider

import com.cla.clip.shizuku.ClipboardBridgeClipPayload
import com.cla.clip.shizuku.ClipboardBridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory

/** Provider 剪贴 payload 临时文件测试，保护 eventId 隔离和过期清理边界。 */
class ClipboardBridgeClipPayloadStoreTest {
    /** 被测剪贴 payload 临时存储管理器。 */
    private val payloadStore = ClipboardBridgeClipPayloadStore()

    @Test
    /** 标准 clip/<eventId> 路径应解析出 eventId。 */
    fun parseEventIdAcceptsClipPath() {
        /** Provider URI 路径段。 */
        val segments = listOf("clip", "event-123")

        assertEquals("event-123", payloadStore.parseEventId(segments))
    }

    @Test
    /** 非 clip 路径必须拒绝，避免 Provider 开放额外敏感文件写入口。 */
    fun parseEventIdRejectsUnsupportedPath() {
        /** 非法 Provider URI 路径段。 */
        val segments = listOf("icon", "event-123")

        assertThrows(FileNotFoundException::class.java) {
            payloadStore.parseEventId(segments)
        }
    }

    @Test
    /** eventId 包含路径字符时必须拒绝，避免写出临时目录。 */
    fun parseEventIdRejectsUnsafeEventId() {
        /** 带路径穿越意图的 Provider URI 路径段。 */
        val segments = listOf("clip", "../bad")

        assertThrows(FileNotFoundException::class.java) {
            payloadStore.parseEventId(segments)
        }
    }

    @Test
    /** 过期清理只删除超时 payload 半文件，不能误删新文件、无关文件或目录。 */
    fun cleanupExpiredFilesDeletesOnlyExpiredPayloadFiles() {
        /** 本次测试使用的临时目录。 */
        val dir = createTempDirectory(prefix = "clipboard_bridge_clip_payloads_test").toFile()

        try {
            /** 固定当前时间，避免测试依赖真实系统时间。 */
            val now = 1_000_000L
            /** 超过 10 分钟 TTL 的旧 payload 半文件。 */
            val expiredPayload = File(dir, "old.tmp").apply {
                writeText("old")
                setLastModified(now - 11 * 60 * 1000L)
            }
            /** 未超过 TTL 的新 payload 半文件。 */
            val freshPayload = File(dir, "fresh.tmp").apply {
                writeText("fresh")
                setLastModified(now - 60 * 1000L)
            }
            /** 非 payload 文件不属于 Provider 临时剪贴内容，清理时必须跳过。 */
            val unrelatedFile = File(dir, "notes.json").apply {
                writeText("keep")
                setLastModified(now - 11 * 60 * 1000L)
            }
            /** 目录项不属于临时 payload 文件，清理时必须跳过。 */
            val childDir = File(dir, "nested").apply {
                mkdirs()
                setLastModified(now - 11 * 60 * 1000L)
            }

            assertEquals(1, payloadStore.cleanupExpiredFiles(dir, now))
            assertFalse(expiredPayload.exists())
            assertTrue(freshPayload.exists())
            assertTrue(unrelatedFile.exists())
            assertTrue(childDir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    /** payload 必须同时满足版本、eventId 和捕获时间校验。 */
    fun isPayloadAcceptedRequiresVersionEventIdAndCapturedTime() {
        /** 合法 payload，代表 Shizuku 已经按当前协议写入临时文件。 */
        val validPayload = ClipboardBridgeClipPayload(
            version = ClipboardBridgeContract.CLIP_PAYLOAD_VERSION,
            eventId = "event-123",
            capturedAtMillis = 100L,
            mimeTypes = listOf("text/plain"),
            text = "hello",
            htmlText = null
        )

        assertTrue(payloadStore.isPayloadAccepted(validPayload, "event-123"))
        assertFalse(payloadStore.isPayloadAccepted(validPayload.copy(version = -1), "event-123"))
        assertFalse(payloadStore.isPayloadAccepted(validPayload.copy(eventId = "other"), "event-123"))
        assertFalse(payloadStore.isPayloadAccepted(validPayload.copy(capturedAtMillis = 0L), "event-123"))
    }
}
