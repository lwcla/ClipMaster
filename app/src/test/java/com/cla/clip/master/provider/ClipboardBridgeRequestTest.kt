package com.cla.clip.master.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Provider read_clip 参数解析测试，保护命令行入口的失败契约。 */
class ClipboardBridgeRequestTest {
    @Test
    /** 合法原始字段应解析成请求对象，并保留非空来源字段。 */
    fun fromValuesParsesValidRequest() {
        /** 解析后的 Provider 请求。 */
        val request = ClipboardBridgeRequest.fromValues(
            eventId = "event-123",
            packageName = "com.example.source",
            appName = "示例应用",
            iconHash = "abc123"
        )


        assertEquals("event-123", request?.eventId)
        assertEquals("com.example.source", request?.packageName)
        assertEquals("示例应用", request?.appName)
        assertEquals("abc123", request?.iconHash)
    }

    @Test
    /** eventId 缺失或包含路径字符时必须拒绝，避免临时文件路径穿越。 */
    fun fromValuesRejectsInvalidEventId() {
        assertNull(
            ClipboardBridgeRequest.fromValues(
                eventId = "../bad",
                packageName = null,
                appName = null,
                iconHash = null
            )
        )
    }

    @Test
    /** 空白来源字段会被规范化为 null，让入库沿用未知来源兜底。 */
    fun fromValuesNormalizesBlankOptionalFields() {
        /** 仅 eventId 有效的请求。 */
        val request = ClipboardBridgeRequest.fromValues(
            eventId = "event",
            packageName = "",
            appName = " ",
            iconHash = ""
        )

        assertEquals("event", request?.eventId)
        assertNull(request?.packageName)
        assertNull(request?.appName)
        assertNull(request?.iconHash)
    }
}
