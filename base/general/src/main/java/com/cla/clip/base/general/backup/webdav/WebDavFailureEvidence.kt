package com.cla.clip.base.general.backup.webdav

import com.cla.clip.base.general.backup.BackupFailure

/** 协程堆栈恢复可能复制 RemoteFailed 并以原异常为 cause；有界展开同类包装，保持原始请求证据。 */
internal fun BackupFailure.RemoteFailed.webDavEvidence(): Throwable? {
    /** 从 cause 开始，不将备份包装本身当成 HTTP/网络错误。 */
    var evidence = cause
    // depth 限制恶意循环或过深包装；正常堆栈恢复只增加一层。
    for (depth in 0 until 8) {
        if (evidence !is BackupFailure.RemoteFailed) return evidence
        evidence = evidence.cause
    }
    return null
}
