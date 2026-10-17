package com.cla.clip.master.ui.page.image

import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.master.image.download.ImageRequestHeaderBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 弹层的轻量响应头读取；只返回元信息，不持有页面、不写库、不下载整图。 */
internal class ImagePreviewMetadataReader(
    /** 延迟取得网络客户端，打开预览之前不初始化连接。 */
    private val calls: () -> Call.Factory,
) {
    /** HEAD 无有效头时回退到单字节 Range；网络失败向调用方传播，取消不应降级重试。 */
    suspend fun read(candidate: ImageCandidateData): ImagePreviewMeta {
        // 复用下载请求头，保证 Cookie、Referer 与内容协商一致。
        val request = ImageRequestHeaderBuilder.buildImageRequest(candidate.url, candidate.referer, candidate.userAgent, candidate.cookie)
        // 只消费响应头并立即关闭响应体，不读取图片字节。
        val head = readHeaders(request.newBuilder().head().build())
        return if (head.mimeType != null || head.contentLength != null) head
        else readHeaders(request.newBuilder().get().header("Range", "bytes=0-0").build())
    }

    /** 将回调桥接为可取消挂起；只向活动调用者交付不可变元信息。 */
    private suspend fun readHeaders(request: Request): ImagePreviewMeta = suspendCancellableCoroutine { continuation ->
        // 一次调用只持有当前 HEAD 或 Range 请求，不取消其他页面共用的客户端。
        val call = calls().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            /** 请求失败交还调用方；取消后的迟到异常不覆盖页面结果。 */
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            /** 立即关闭响应体；Range 返回的总长优先于单字节 Content-Length。 */
            override fun onResponse(call: Call, response: Response) {
                // 解析值与响应资源分离，取消竞态不会泄漏响应体。
                val meta = response.use {
                    if (!it.isSuccessful) ImagePreviewMeta() else ImagePreviewMeta(
                        mimeType = it.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty),
                        contentLength = it.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                            ?: it.header("Content-Length")?.toLongOrNull(),
                    )
                }
                if (continuation.isActive) continuation.resume(meta)
            }
        })
    }
}
