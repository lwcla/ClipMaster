package com.cla.clip.base.general.backup.webdav

import com.cla.clip.base.general.backup.BackupFailure
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 仅公开上传可恢复性；不让 app 层依赖请求实现或按错误文案猜测网络状态。 */
object WebDavUploadRetryClassifier {
    /** 只接受 WebDAV 包装中的明确网络类型；其他异常、未知包装和取消均不能自动补传。 */
    fun isRecoverable(failure: Throwable): Boolean {
        /** 复用有界证据展开，TLS/HTTP 包装不会被跳过而误命中内部超时。 */
        val evidence = (failure as? BackupFailure.RemoteFailed)?.webDavEvidence()
        return evidence is UnknownHostException || evidence is SocketTimeoutException ||
            evidence is ConnectException || evidence is NoRouteToHostException
    }
}
