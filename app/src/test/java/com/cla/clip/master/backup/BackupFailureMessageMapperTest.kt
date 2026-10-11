package com.cla.clip.master.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 明确失败提示必须基于稳定证据，不能靠耗时或截图猜测。 */
class BackupFailureMessageMapperTest {
    /** 已知 WebDAV 证据不能继续降级未知，也不能与系统执行时限混淆。 */
    @Test fun webDavEvidenceHasSpecificPresentation() {
        // code 为本次已确认的稳定原因，非真实服务返回内容。
        for (code in listOf("webdav_request_timeout", "webdav_tls_failed", "webdav_network_failed",
            "webdav_http_404", "webdav_http_409", "webdav_http_503", "webdav_directory_create_failed")) {
            org.junit.Assert.assertNotEquals(code, BackupFailureMessage.Unknown, BackupFailureMessageMapper.map(code))
        }
        org.junit.Assert.assertNotEquals(BackupFailureMessageMapper.map("upload_interrupted_timeout"),
            BackupFailureMessageMapper.map("webdav_request_timeout"))
        org.junit.Assert.assertNotEquals(BackupFailureMessageMapper.map(""), BackupFailureMessageMapper.map("garbage"))
    }

    /** 状态码格式不合法时保持未知，不能解释服务端原文或拼接注入文本。 */
    @Test fun malformedHttpReasonsStayUnknown() {
        // code 覆盖越界、多余符号、空状态及伪造后缀。
        for (code in listOf("webdav_http_399", "webdav_http_600", "webdav_http_+404", "webdav_http_", "webdav_http_404x")) {
            assertEquals(BackupFailureMessage.Unknown, BackupFailureMessageMapper.map(code))
        }
    }
    /** 旧中断与明确超时/配额各有独立分类。 */
    @Test fun interruptionAndInfrastructureErrorsAreActionable() {
        assertEquals(BackupFailureMessage.Interrupted, BackupFailureMessageMapper.map("upload_interrupted"))
        assertEquals(BackupFailureMessage.Interrupted, BackupFailureMessageMapper.map("generation_interrupted"))
        assertEquals(BackupFailureMessage.Interrupted, BackupFailureMessageMapper.map("local_interrupted"))
        assertEquals(BackupFailureMessage.Timeout, BackupFailureMessageMapper.map("upload_interrupted_timeout"))
        assertEquals(BackupFailureMessage.Quota, BackupFailureMessageMapper.map("upload_interrupted_quota"))
        assertEquals(BackupFailureMessage.Network, BackupFailureMessageMapper.map("upload_interrupted_network"))
        assertEquals(BackupFailureMessage.DeviceState, BackupFailureMessageMapper.map("upload_interrupted_device"))
        assertEquals(BackupFailureMessage.Cancelled, BackupFailureMessageMapper.map("upload_interrupted_cancelled"))
        assertEquals(BackupFailureMessage.Schedule, BackupFailureMessageMapper.map("schedule_failed"))
        assertEquals(BackupFailureMessage.Handoff, BackupFailureMessageMapper.map("handoff_failed"))
        assertEquals(BackupFailureMessage.Auth, BackupFailureMessageMapper.map("auth_failed"))
        assertEquals(BackupFailureMessage.Unknown, BackupFailureMessageMapper.map("unexpected_error"))
    }
}
