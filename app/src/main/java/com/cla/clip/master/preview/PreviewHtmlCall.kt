package com.cla.clip.master.preview

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * 自动预览使用的可取消 HTML 读取：非成功响应返回 null，IO 错误向调用方传播，响应总会关闭。
 * 最多读取 maxBytes 字节；协程取消直接取消实际 Call，不只丢弃解析结果。
 */
internal suspend fun Call.awaitPreviewHtml(maxBytes: Long): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        /** HTTP 失败保留异常契约；调用方只记录异常类型，不记录包含 URL 的异常原文。 */
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }

        /** 在 OkHttp 工作线程读取有上限的响应，已取消时仅释放响应。 */
        override fun onResponse(call: Call, response: Response) {
            /** 资源释放与读取一并捕获，避免回调线程异常导致调用方一直挂起。 */
            val result = runCatching {
                response.use {
                    if (!continuation.isActive || !it.isSuccessful) null
                    else it.peekBody(maxBytes).use { body -> body.string() }
                }
            }
            continuation.resumeWith(result)
        }
    })
}
