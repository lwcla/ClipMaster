package com.cla.clip.feature.torrent.search.provider.builtin.megapeer

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** MegaPeer 固定 browse.php URL 构造器。 */
internal class MegaPeerUrlBuilder {
    /** 结构化编码查询和固定请求参数。 */
    fun searchUrl(
        /** 原始用户查询。 */
        query: String,
        /** 领域分类。 */
        category: Category,
    ): String {
        /** categoryId 按固定上游映射，非支持分类回退 0。 */
        val categoryId = when (category) {
            Category.APPS -> 29
            Category.BOOKS -> 52
            Category.GAMES -> 28
            Category.MOVIES -> 80
            Category.MUSIC -> 94
            Category.OTHER -> 59
            Category.SERIES -> 6
            else -> 0
        }
        return BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("browse.php")
            .addQueryParameter("search", sanitizeProviderQuery(query))
            .addQueryParameter("age", "")
            .addQueryParameter("cat", categoryId.toString())
            .addQueryParameter("stype", "0")
            .addQueryParameter("sort", "0")
            .addQueryParameter("ascdesc", "0")
            .build()
            .toString()
    }

    /** 固定 URL 常量。 */
    companion object {
        /** 审计通过的站点根地址。 */
        const val BASE_URL = "https://megapeer.vip"
    }
}

/** MegaPeer 精确 HTTPS/default-port URL 策略。 */
internal class MegaPeerUrlPolicy {
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

    /** URL 策略常量。 */
    private companion object {
        /** 精确站点 host。 */
        private const val HOST = "megapeer.vip"
        /** HTTPS 默认端口。 */
        private const val HTTPS_PORT = 443
    }
}
