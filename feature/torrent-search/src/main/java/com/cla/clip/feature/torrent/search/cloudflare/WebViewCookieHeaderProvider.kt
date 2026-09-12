package com.cla.clip.feature.torrent.search.cloudflare

import android.webkit.CookieManager
import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

/** 按请求 URL 读取 WebView Cookie 的窄接口；调用方不得枚举或记录 Cookie。 */
internal fun interface WebViewCookieHeaderProvider {
    /** 返回当前 URL 可发送的 Cookie 请求头；没有挑战状态时返回 null。 */
    fun cookiesFor(url: String): String?
}

/** Android WebView 全局 CookieManager 的只读桥接实现。 */
internal class AndroidWebViewCookieHeaderProvider @Inject constructor() : WebViewCookieHeaderProvider {
    /** 即时读取目标 URL Cookie，不缓存、不持久化副本且不读取异常正文。 */
    override fun cookiesFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { /** cookieHeader 是 WebView 已按 URL 筛选的请求头。 */ cookieHeader ->
            cookieHeader.isNotBlank()
        }
    /** WebView 实现不可用时安静降级为无 Cookie 请求。 */
    } catch (failure: RuntimeException) {
        null
    }
}

/** 把 WebView 按 URL 返回的 Cookie 注入尚未显式声明 Cookie 的 OkHttp 请求。 */
internal class WebViewCookieRequestInterceptor(
    /** Cookie 来源只允许按当前请求 URL 查询。 */
    private val cookieHeaderProvider: WebViewCookieHeaderProvider,
) : Interceptor {
    /** 执行单次注入；Cookie 内容绝不进入日志、异常或状态。 */
    override fun intercept(chain: Interceptor.Chain): Response {
        /** request 是 Provider 构造的原始请求。 */
        val request = chain.request()
        if (request.header("Cookie") != null) return chain.proceed(request)
        /** cookieHeader 只在当前栈帧内用于构造请求。 */
        val cookieHeader = cookieHeaderProvider.cookiesFor(request.url.toString())
        /** bridgedRequest 在无 Cookie 时复用原对象，避免无意义复制。 */
        val bridgedRequest = if (cookieHeader.isNullOrBlank()) request else request.newBuilder().header("Cookie", cookieHeader).build()
        return chain.proceed(bridgedRequest)
    }
}
