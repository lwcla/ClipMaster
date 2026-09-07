package com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TokyoToshokan 结构化搜索与首页 URL 构造器。 */
internal class TokyoToshokanUrlBuilder {
    /** 查询、类别和固定匹配开关均由 HttpUrl 编码。 */
    fun searchUrl(
        /** 原始用户查询。 */ query: String,
        /** 当前领域分类。 */ category: Category,
    ): String = BASE_URL.toHttpUrl().newBuilder()
        .addPathSegment("search.php")
        .addQueryParameter("terms", sanitizeProviderQuery(query))
        .addQueryParameter("type", TokyoToshokanCategoryMapper.requestId(category))
        .addQueryParameter("searchName", "true")
        .build()
        .toString()

    /** 首页仅对受支持的非 ALL 分类添加 cat 参数。 */
    fun latestUrl(
        /** 当前领域分类。 */ category: Category,
    ): String {
        /** builder 固定生成审核站点的 index.php。 */ val builder = BASE_URL.toHttpUrl().newBuilder().addPathSegment("index.php")
        /** categoryId 缺失表示 ALL 或无原生浏览分类。 */ val categoryId = TokyoToshokanCategoryMapper.browseId(category)
        if (categoryId != null) builder.addQueryParameter("cat", categoryId)
        return builder.build().toString()
    }

    /** URL 构造固定站点。 */ private companion object { /** 审核通过的根地址。 */ private const val BASE_URL = "https://tokyotosho.info" }
}

/** TokyoToshokan 同站输入和可选输出 URL 策略。 */
internal class TokyoToshokanUrlPolicy {
    /** 只允许精确站点 host。 */ private val delegate = BuiltinProviderUrlPolicy(setOf("tokyotosho.info"))

    /** 网络前要求 HTTPS、默认端口、无用户信息且 host 精确匹配。 */
    fun requireSiteUrl(
        /** 待验证网络地址。 */ url: String,
    ): String = delegate.requireHttpsUrl(url).toString()

    /** 相对可选链接解析失败或越界时降级为空。 */
    fun optionalSiteUrl(
        /** 已验证页面基址。 */ pageUrl: String,
        /** 未信任 href。 */ candidate: String?,
    ): String? = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()

    /** 仅读取精确 cat 查询参数，路径或其他参数不能冒充类别。 */
    fun optionalCategoryId(
        /** 未信任类别 href。 */ candidate: String?,
    ): String? {
        /** parsedUrl 用审核根地址离线解析相对链接。 */ val parsedUrl = BASE_URL.toHttpUrl().resolve(candidate.orEmpty()) ?: return null
        /** trustedUrl 防止跨站类别链接参与映射。 */ val trustedUrl = runCatching { delegate.requireHttpsUrl(parsedUrl.toString()) }.getOrNull() ?: return null
        return trustedUrl.queryParameter("cat")
    }

    /** 地址解析固定站点。 */ private companion object { /** 相对链接基址。 */ private const val BASE_URL = "https://tokyotosho.info" }
}
