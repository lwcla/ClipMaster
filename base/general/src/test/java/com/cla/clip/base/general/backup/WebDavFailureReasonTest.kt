package com.cla.clip.base.general.backup

import com.cla.clip.base.general.backup.webdav.WebDavHttpFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/** 请求异常类型与固定状态才是原因依据，合成异常文本不参与分类。 */
class WebDavFailureReasonTest {
    /** 请求超时、TLS 与其他网络错误必须分开保存，旧无证据失败仍保留聚合码。 */
    @Test fun requestFailuresRetainEvidenceWithoutMessages() {
        assertEquals("webdav_request_timeout", BackupFailure.RemoteFailed(SocketTimeoutException("synthetic")).backupReasonCode())
        assertEquals("webdav_tls_failed", BackupFailure.RemoteFailed(SSLHandshakeException("synthetic")).backupReasonCode())
        assertEquals("webdav_network_failed", BackupFailure.RemoteFailed(IOException("synthetic")).backupReasonCode())
        assertEquals("remote_failed", BackupFailure.RemoteFailed().backupReasonCode())
        assertEquals("unexpected_error", IOException("synthetic").backupReasonCode())
    }

    /** HTTP 状态用于真实上传错误，目录创建失败保留独立操作语义。 */
    @Test fun httpAndDirectoryFailuresPreserveSpecificReasons() {
        assertEquals("webdav_http_409", BackupFailure.RemoteFailed(WebDavHttpFailure("upload_file", 409)).backupReasonCode())
        assertEquals("webdav_directory_create_failed", BackupFailure.RemoteFailed(WebDavHttpFailure("mkcol", 507)).backupReasonCode())
        assertEquals("webdav_http_409", BackupFailure.RemoteFailed(
            BackupFailure.RemoteFailed(WebDavHttpFailure("upload_file", 409))).backupReasonCode())
    }
}
