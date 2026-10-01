package com.cla.clip.master.backup.upload

import com.cla.clip.base.general.backup.settings.BackupSettingsSnapshot
import org.junit.Assert.*
import org.junit.Test

/** 目标摘要只绑定真实目的地，不输出地址和凭据。 */
class BackupUploadTargetFingerprintTest {
    /** 编码斜杠是路径段的一部分，不能与实际目录分隔符合并。 */
    @Test fun encodedPathSeparatorKeepsDistinctIdentity() {
        /** 用合成地址覆盖 URL 解码导致身份碰撞的边界。 */
        val encoded = BackupSettingsSnapshot(webDavEndpoint = "https://example.invalid/base%2Fsegment")
        assertNotEquals(BackupUploadTargetFingerprint.of(encoded),
            BackupUploadTargetFingerprint.of(encoded.copy(webDavEndpoint = "https://example.invalid/base/segment")))
    }

    /** URL 的大小写和默认端口等价表示不能误取消等待包。 */
    @Test fun equivalentEndpointRepresentationsShareIdentity() {
        /** 第一份合成目标。 */
        val first = BackupSettingsSnapshot(webDavEndpoint = "https://example.invalid/", webDavUsername = "account")
        /** 同目标的合法等价拼写。 */
        val equivalent = first.copy(webDavEndpoint = "HTTPS://EXAMPLE.INVALID:443")
        assertEquals(BackupUploadTargetFingerprint.of(first), BackupUploadTargetFingerprint.of(equivalent))
        assertNotEquals(BackupUploadTargetFingerprint.of(first), BackupUploadTargetFingerprint.of(first.copy(webDavUsername = "another")))
        assertNotEquals(BackupUploadTargetFingerprint.of(first), BackupUploadTargetFingerprint.of(first.copy(webDavRemoteDir = "/another/")))
    }
}
