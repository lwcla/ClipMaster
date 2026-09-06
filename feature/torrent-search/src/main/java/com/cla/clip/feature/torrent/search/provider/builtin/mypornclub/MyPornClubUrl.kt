package com.cla.clip.feature.torrent.search.provider.builtin.mypornclub

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** MyPornClub 固定路径构造器。 */
internal class MyPornClubUrlBuilder {
    /** 保留上游仅针对搜索 query 的字面 percent20 转换。 */
    fun searchUrl(
        /** 原始用户查询。 */
        query: String,
    ): String {
        /** formattedQuery 先清洗控制字符，再执行站点专属字面替换。 */
        val formattedQuery = sanitizeProviderQuery(query).replace("%20", "-")
        return BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("s")
            .addPathSegment(formattedQuery)
            .addPathSegment("seeders")
            .build()
            .toString()
    }

    /** 返回固定最新列表地址。 */
    fun latestUrl(): String = "$BASE_URL/ts/latest/alltime"

    /** 返回固定热门列表地址。 */
    fun topUrl(): String = "$BASE_URL/ts/hits/alltime"

    /** 固定 URL 常量。 */
    companion object {
        /** 审计通过的站点根地址。 */
        const val BASE_URL = "https://myporn.club"
    }
}

/** MyPornClub 精确 HTTPS/default-port URL 策略。 */
internal class MyPornClubUrlPolicy {
    /** 公共精确 host 校验器。 */
    private val delegate = BuiltinProviderUrlPolicy(setOf(HOST))

    /** 网络前校验固定站点 URL。 */
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

    /** URL 策略常量。 */
    private companion object {
        /** 精确站点 host。 */
        private const val HOST = "myporn.club"
        /** HTTPS 默认端口。 */
        private const val HTTPS_PORT = 443
    }
}
