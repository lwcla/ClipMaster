package com.cla.clip.feature.torrent.search.provider.builtin.mikan

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Mikan 固定请求 URL 构造器。 */
internal class MikanUrlBuilder {
    /** 构造结构化搜索 query。 */
    fun searchUrl(
        /** 原始用户查询。 */
        query: String,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegments("Home/Search")
        .addQueryParameter("searchstr", sanitizeProviderQuery(query))
        .build()
        .toString()

    /** 固定 URL 常量。 */
    companion object {
        /** 审计通过的站点根地址。 */
        const val BASE_URL = "https://mikanani.me"
    }
}

/** Mikan 精确 HTTPS/default-port URL 策略。 */
internal class MikanUrlPolicy {
    /** 公共精确 host 校验器。 */
    private val delegate = BuiltinProviderUrlPolicy(setOf(HOST))

    /** 网络前要求固定站点 URL。 */
    fun requireUrl(
        /** 不可信 URL。 */
        url: String,
    ): String {
        /** parsedUrl 是公共策略校验后的地址。 */
        val parsedUrl = delegate.requireHttpsUrl(url)
        if (parsedUrl.port != HTTPS_PORT || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) {
            throw HttpDeniedException()
        }
        return parsedUrl.toString()
    }

    /** 可选同站链接非法时降级为空。 */
    fun optionalUrl(
        /** 已验证页面基址。 */
        pageUrl: String,
        /** 不可信链接候选。 */
        candidate: String?,
    ): String? = runCatching {
        /** resolved 是公共策略解析后的同站地址。 */
        delegate.resolveOptionalHttpsUrl(pageUrl, candidate)?.also { resolved ->
            /** parsedUrl 用于补充端口与用户信息检查。 */
            val parsedUrl = resolved.toHttpUrl()
            if (parsedUrl.port != HTTPS_PORT || parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) throw HttpDeniedException()
        }
    }.getOrNull()

    /** 仅保留固定 Download 路径的可选文件地址。 */
    fun optionalDownloadUrl(
        /** 已验证页面基址。 */
        pageUrl: String,
        /** 不可信链接候选。 */
        candidate: String?,
    ): String? = optionalUrl(pageUrl, candidate)?.takeIf {
        /** resolved 是已通过同站检查的文件地址。 */ resolved ->
        resolved.toHttpUrl().encodedPath.startsWith("/Download/")
    }

    /** URL 策略常量。 */
    private companion object {
        /** 精确站点 host。 */
        private const val HOST = "mikanani.me"
        /** HTTPS 默认端口。 */
        private const val HTTPS_PORT = 443
    }
}
