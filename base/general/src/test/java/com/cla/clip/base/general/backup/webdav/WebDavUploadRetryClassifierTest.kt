package com.cla.clip.base.general.backup.webdav

import com.cla.clip.base.general.backup.BackupFailure
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** 只允许明确的请求网络异常补传，不能把本地 IO 或错误正文当重试依据。 */
class WebDavUploadRetryClassifierTest {
    /** 四种暂时网络失败均可申请业务重试，嵌套包装保留同一判断。 */
    @Test fun dnsAndSocketTimeoutAreRecoverable() {
        // failure 为类型化请求证据；message 刻意与分类无关。
        for (failure in listOf(UnknownHostException("private"), SocketTimeoutException("private"),
            ConnectException("private"), NoRouteToHostException("private"))) {
            assertTrue(WebDavUploadRetryClassifier.isRecoverable(BackupFailure.RemoteFailed(failure)))
            assertTrue(WebDavUploadRetryClassifier.isRecoverable(BackupFailure.RemoteFailed(BackupFailure.RemoteFailed(failure))))
            assertFalse(WebDavUploadRetryClassifier.isRecoverable(failure))
        }
    }

    /** 取消、证书、协议和未知 IO 不能因内层出现超时而获得重试。 */
    @Test fun tlsHttpLocalIoAndCancellationAreNotRecoverable() {
        // failure 为不可恢复证据，HTTP 404/409 保留原有目录修复边界。
        for (failure in listOf(IOException("timeout"), SocketException("reset"),
            SSLHandshakeException("private").apply { initCause(SocketTimeoutException()) },
            CancellationException("private"), WebDavHttpFailure("upload_file", 401),
            WebDavHttpFailure("upload_file", 404), WebDavHttpFailure("upload_file", 409),
            WebDavHttpFailure("upload_file", 503))) {
            assertFalse(WebDavUploadRetryClassifier.isRecoverable(BackupFailure.RemoteFailed(failure)))
        }
        assertFalse(WebDavUploadRetryClassifier.isRecoverable(CancellationException()))
    }

    /** 损坏或过深包装必须有界退出，不能凭未知证据重传。 */
    @Test fun excessiveAndCyclicRemoteWrappersAreRejected() {
        /** 合成多层协程包装，超过既有证据展开上限。 */
        var failure: Throwable = UnknownHostException()
        repeat(12) { failure = BackupFailure.RemoteFailed(failure) }
        assertFalse(WebDavUploadRetryClassifier.isRecoverable(failure))
        /** 未知 IO 自循环不能诱导分类器遍历敏感或无限 cause。 */
        val cyclic = object : IOException() {
            /** 测试专用恶意 cause，仅用于验证有界分类。 */
            override val cause: Throwable get() = this
        }
        assertFalse(WebDavUploadRetryClassifier.isRecoverable(BackupFailure.RemoteFailed(cyclic)))
    }
}
