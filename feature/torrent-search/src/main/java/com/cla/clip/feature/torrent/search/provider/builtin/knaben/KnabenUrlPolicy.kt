package com.cla.clip.feature.torrent.search.provider.builtin.knaben

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Knaben 批次特有 URL 边界；在通用 host/scheme 校验上补默认端口和用户信息约束。 */
internal class KnabenUrlPolicy {
    /** manifest 审计通过的主页与 API host。 */
    private val commonPolicy = BuiltinProviderUrlPolicy(setOf("knaben.org", "api.knaben.org"))

    /** 返回唯一固定 API 地址，并执行与外部字段一致的严格校验。 */
    fun requireApiUrl(): String = requireExactHttpsUrl(API_URL)

    /** 可选详情非法时返回 null，避免单条非必填字段破坏合法 torrent。 */
    fun optionalDetailsUrl(
        /** 上源返回的未信任详情地址。 */
        candidate: String?,
    ): String? = candidate?.takeIf(String::isNotBlank)?.let {
        /** rawUrl 是仅供安全策略解析的可选地址。 */
        rawUrl -> runCatching { requireExactHttpsUrl(rawUrl) }.getOrNull()
    }

    /** 精确接受白名单 HTTPS 默认端口且禁止用户信息。 */
    private fun requireExactHttpsUrl(
        /** 待校验地址，禁止进入异常正文。 */
        url: String,
    ): String {
        /** parsedUrl 用于补充通用 policy 尚未表达的端口和用户信息检查。 */
        val parsedUrl = url.toHttpUrlOrNull() ?: throw HttpDeniedException()
        if (parsedUrl.port != 443 || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) throw HttpDeniedException()
        return commonPolicy.requireHttpsUrl(url).toString()
    }

    /** 固定 Knaben API 端点。 */
    private companion object {
        /** 所有三个入口唯一允许请求的地址。 */
        private const val API_URL = "https://api.knaben.org/v1"
    }
}
