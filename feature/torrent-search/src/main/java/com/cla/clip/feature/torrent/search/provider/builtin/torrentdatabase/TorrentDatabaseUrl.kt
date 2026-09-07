package com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TorrentDatabase 搜索与浏览路径构造器。 */
internal class TorrentDatabaseUrlBuilder {
    /** 构造 newest 搜索地址，只有七个原生分类添加 category。 */
    fun searchUrl(
        /** 原始用户查询。 */ query: String,
        /** 当前领域分类。 */ category: Category,
    ): String {
        /** builder 先固定 newest 和清洗后的 q。 */ val builder = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("newest")
            .addQueryParameter("q", sanitizeProviderQuery(query))
        /** categorySlug 缺失表示 ALL/ANIME/OTHER 不添加分类。 */ val categorySlug = TorrentDatabaseCategoryMapper.searchSlug(category)
        if (categorySlug != null) builder.addQueryParameter("category", categorySlug)
        return builder.build().toString()
    }

    /** 构造 newest 或 newest_类别路径，BOOKS 固定使用 ebooks。 */
    fun latestUrl(
        /** 当前领域分类。 */ category: Category,
    ): String {
        /** categorySlug 缺失时使用无后缀 newest。 */ val categorySlug = TorrentDatabaseCategoryMapper.browseSlug(category)
        /** pathSegment 根据是否有原生分类选择固定路径。 */ val pathSegment = categorySlug?.let { slug -> "newest_$slug" } ?: "newest"
        return BASE_URL.toHttpUrl().newBuilder().addPathSegment(pathSegment).build().toString()
    }

    /** ALL 映射独立 newest，其余仅有原生类别时生成 top_seeded 路径。 */
    fun topUrl(
        /** 当前领域分类。 */ category: Category,
    ): String? {
        if (category == Category.ALL) return latestUrl(Category.ALL)
        /** categorySlug 对 ANIME/OTHER 返回空，调用方据此避免网络。 */ val categorySlug = TorrentDatabaseCategoryMapper.browseSlug(category) ?: return null
        return BASE_URL.toHttpUrl().newBuilder().addPathSegment("top_seeded_$categorySlug").build().toString()
    }

    /** URL 构造固定站点。 */ private companion object { /** 审核根地址。 */ private const val BASE_URL = "https://developify.ca" }
}

/** TorrentDatabase 同站输入、可选详情输出和 marker 离线策略。 */
internal class TorrentDatabaseUrlPolicy {
    /** 仅允许精确 developify.ca。 */ private val delegate = BuiltinProviderUrlPolicy(setOf("developify.ca"))

    /** 网络前要求审核 HTTPS authority。 */
    fun requireSiteUrl(
        /** 待验证网络地址。 */ url: String,
    ): String = delegate.requireHttpsUrl(url).toString()

    /** 相对详情链接解析失败或越界时降级为空。 */
    fun optionalDetailsUrl(
        /** 已验证页面基址。 */ pageUrl: String,
        /** 未信任详情 href。 */ candidate: String?,
    ): String? = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull()

    /** marker 必须是同站 HTTPS 的 track/magnet/hash 精确三段路径，hash 只取路径。 */
    fun markerInfoHash(
        /** 已验证页面基址。 */ pageUrl: String,
        /** 未信任 marker href。 */ candidate: String?,
    ): String? {
        /** resolvedUrl 用同一策略解析相对地址并校验 authority。 */ val resolvedUrl = runCatching { delegate.resolveOptionalHttpsUrl(pageUrl, candidate) }.getOrNull() ?: return null
        /** parsedUrl 已由通用策略保证安全，此处只判路径。 */ val parsedUrl = resolvedUrl.toHttpUrl()
        /** segments 必须恰好为三段，query 不参与 hash 来源。 */ val segments = parsedUrl.pathSegments
        if (segments.size != 3 || segments[0] != "track" || segments[1] != "magnet") return null
        return MagnetInfoHashParser.normalize(segments[2])
    }
}
