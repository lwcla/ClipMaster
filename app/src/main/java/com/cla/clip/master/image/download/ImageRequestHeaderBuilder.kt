package com.cla.clip.master.image.download

import okhttp3.Request

/**
 * 图片下载请求头构建器。
 *
 * 统一维护 Worker 与预览链路接近浏览器图片加载的请求语义，避免多个下载入口各自拼 Accept、Referer、User-Agent
 * 和 Cookie，导致 CDN 内容协商结果不一致。
 */
object ImageRequestHeaderBuilder {
    /** 浏览器图片请求常见 Accept；用于降低 CDN 因 OkHttp 默认 Accept 缺失而返回静态降级图的概率。 */
    const val IMAGE_REQUEST_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

    /**
     * 构建单张图片下载请求。
     *
     * Referer、User-Agent、Cookie 均来自提取阶段记录，空值时不写入，避免覆盖 OkHttp 或服务端默认行为。
     */
    fun buildImageRequest(url: String, referer: String?, userAgent: String?, cookie: String?): Request {
        return Request.Builder().url(url).apply {
            // 与 WebView/浏览器图片加载保持相近的内容协商条件，避免服务端因缺少 Accept 返回静态转码版本。
            header("Accept", IMAGE_REQUEST_ACCEPT)
            if (!referer.isNullOrBlank()) header("Referer", referer)
            if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
        }.build()
    }
}
