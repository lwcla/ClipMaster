package com.cla.clip.base.general.backup.webdav

import com.cla.clip.base.general.backup.BackupFailure
import com.cla.clip.base.general.backup.backupReasonCode
import com.cla.clip.base.general.utils.logW
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 只负责 HTTP 取消、响应消费和释放；业务身份与重调度仍由调用方管理，不持有 Android 状态。 */
internal class WebDavCallExecutor(
    /** 复用派生客户端的连接池、超时和单次请求策略。 */
    private val client: OkHttpClient,
) {
    /** 取消立即调用 Call.cancel；响应和流在消费结束或异常时关闭，不把取消产生的 IO 异常提交为业务失败。 */
    suspend fun <T> execute(operation: String, request: Request, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            /** 本次调用独占一个 Call，不能复用已执行或已取消请求。 */
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                /** 真正未取消的网络异常才归入 WebDAV 失败；晚到异常不改变已取消协程。 */
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    /** 不保留敏感地址或异常 message，只保留异常类型供稳定分类。 */
                    val failure = BackupFailure.RemoteFailed(e)
                    logW("WebDavClient") { "${webDavFailureLog(operation, "request", e)} reasonCode=${failure.backupReasonCode()}" }
                    continuation.resumeWithException(failure)
                }

                /** 所有响应均在回调线程消费并关闭，取消竞态下不继续执行业务消费。 */
                override fun onResponse(call: Call, response: Response) {
                    try {
                        /** 仅在响应仍属于活跃协程时消费，随后再次检查取消再交付结果。 */
                        val result = response.use { if (!continuation.isActive) return; consume(it) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        if (!continuation.isActive) return
                        /** 响应流异常也保持类型；已有业务失败不被二次包装。 */
                        val mapped = if (failure is IOException) BackupFailure.RemoteFailed(failure) else failure
                        // 活跃响应 IO 同样留固定类型证据；取消不作为新的网络失败打印。
                        if (failure is IOException) logW("WebDavClient") {
                            "${webDavFailureLog(operation, "response", failure)} reasonCode=${mapped.backupReasonCode()}"
                        }
                        continuation.resumeWithException(mapped)
                    }
                }
            })
        }
}
