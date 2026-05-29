package com.cla.clip.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shizuku 剪贴 payload JSON 协议测试，保护正文通过 stdin 传输前后的字段稳定性。 */
class ClipboardBridgeClipPayloadTest {
    @Test
    /** payload 序列化必须保留 eventId、捕获时间、MIME、文本和 HTML 字段。 */
    fun payloadRoundTripKeepsTextHtmlAndMetadata() {
        /** 原始 payload，使用合成文本避免测试夹带真实剪贴内容。 */
        val payload = ClipboardBridgeClipPayload(
            version = ClipboardBridgeContract.CLIP_PAYLOAD_VERSION,
            eventId = "event-1",
            capturedAtMillis = 123_456L,
            mimeTypes = listOf("text/plain", "text/html"),
            text = "hello",
            htmlText = "<b>hello</b>"
        )

        /** 反序列化后的 payload，用于验证 JSON 字段没有丢失。 */
        val decoded = ClipboardBridgeClipPayload.fromJsonString(payload.toJsonString())

        assertEquals(payload, decoded)
    }

    @Test
    /** 显式 null 文本字段应保持为 null，方便 app 侧区分空文本和 HTML fallback。 */
    fun payloadRoundTripKeepsNullTextFields() {
        /** 只有 MIME 类型、没有可用文本的 payload。 */
        val payload = ClipboardBridgeClipPayload(
            version = ClipboardBridgeContract.CLIP_PAYLOAD_VERSION,
            eventId = "event-2",
            capturedAtMillis = 456_789L,
            mimeTypes = listOf("image/png"),
            text = null,
            htmlText = null
        )

        /** 反序列化后的 payload，用于验证 null 字段不会变成字符串 "null"。 */
        val decoded = ClipboardBridgeClipPayload.fromJsonString(payload.toJsonString())

        assertNull(decoded.text)
        assertNull(decoded.htmlText)
        assertEquals(listOf("image/png"), decoded.mimeTypes)
    }
}
