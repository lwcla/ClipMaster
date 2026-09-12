package com.cla.clip.feature.torrent.search.cloudflare

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Cloudflare 挑战 WebView 的同源 HTTP/HTTPS 导航策略。 */
internal class CloudflareNavigationPolicy(
    /** 注册表提供的初始挑战地址。 */
    initialUrl: String,
) {
    /** origin 是已解析且 scheme 合法的初始来源；非法输入保持 null 并拒绝全部。 */
    private val origin: HttpUrl? = initialUrl.toHttpUrlOrNull()?.takeIf { /** url 是待验证 scheme 的初始地址。 */ url ->
        url.scheme == "http" || url.scheme == "https"
    }

    /** 判断目标是否与初始地址保持相同 scheme、host 和有效端口。 */
    fun allows(
        /** WebView 即将加载的不可信目标。 */
        candidateUrl: String?,
    ): Boolean {
        /** expectedOrigin 为空表示构造输入无效。 */
        val expectedOrigin = origin ?: return false
        /** candidate 由 OkHttp 统一解析 host、IPv6 与默认端口。 */
        val candidate = candidateUrl?.toHttpUrlOrNull() ?: return false
        return candidate.scheme == expectedOrigin.scheme &&
            candidate.host == expectedOrigin.host &&
            candidate.port == expectedOrigin.port
    }
}
