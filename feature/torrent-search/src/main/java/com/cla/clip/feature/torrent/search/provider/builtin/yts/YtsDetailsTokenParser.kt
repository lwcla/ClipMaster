package com.cla.clip.feature.torrent.search.provider.builtin.yts

import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import java.net.URI

/** Yts 页面详情 token 的严格构造与解析协作者。 */
internal object YtsDetailsTokenParser {
    /** 页面 token 只允许固定展示 host。 */
    private val pagePolicy = BuiltinProviderUrlPolicy(setOf("yts.bz"))

    /** 将经过校验的页面 URL、电影 ID 与 hash 构造成可逆 token。 */
    fun buildToken(
        /** API 返回的未信任页面 URL。 */
        pageUrl: String,
        /** API 返回的电影 ID。 */
        movieId: Long,
        /** 已由公共解析器规范化的 infoHash。 */
        infoHash: String,
    ): String? {
        if (movieId <= 0L || MagnetInfoHashParser.normalize(infoHash) != infoHash) {
            return null
        }
        /** parsedPage 必须是无 query/fragment 的精确电影页面。 */
        val parsedPage = runCatching { requireMoviePage(pageUrl) }.getOrNull() ?: return null
        return parsedPage.newBuilder()
            .addQueryParameter("movieid", movieId.toString())
            .addQueryParameter("infohash", infoHash)
            .build()
            .toString()
    }

    /** 从外部 token 提取唯一 movieid/infohash 参数，污染结构统一拒绝。 */
    fun requireToken(
        /** 未信任详情 token。 */
        token: String,
    ): YtsDetailsToken {
        /** parsed 必须先通过展示 host 安全边界。 */
        val parsed = pagePolicy.requireHttpsUrl(token)
        /** rawPath 防止点段在规范化后改变路由形状。 */
        val rawPath = runCatching { URI(token).rawPath }.getOrNull() ?: throw HttpDeniedException()
        /** rawSegments 必须是 movies 与单个 slug 两段。 */
        val rawSegments = rawPath.removePrefix("/").split('/')
        if (
            rawSegments.size != 2 ||
            rawSegments[0] != "movies" ||
            rawSegments[1].isBlank() ||
            parsed.pathSegments.size != 2 ||
            parsed.pathSegments[0] != "movies" ||
            parsed.pathSegments[1].isBlank() ||
            parsed.fragment != null ||
            parsed.querySize != 2 ||
            parsed.queryParameterValues("movieid").size != 1 ||
            parsed.queryParameterValues("infohash").size != 1
        ) {
            throw HttpDeniedException()
        }
        /** rawMovieId 只接受规范正整数。 */
        val rawMovieId = parsed.queryParameter("movieid") ?: throw HttpDeniedException()
        /** movieId 的 Long 上限同时限制恶意超长值。 */
        val movieId = rawMovieId.toLongOrNull()?.takeIf { value -> value > 0L }
            ?: throw HttpDeniedException()
        if (rawMovieId != movieId.toString()) {
            throw HttpDeniedException()
        }
        /** infoHash 必须已经是公共规范化值。 */
        val rawInfoHash = parsed.queryParameter("infohash") ?: throw HttpDeniedException()
        /** infoHash 规范化结果用于响应身份匹配。 */
        val infoHash = MagnetInfoHashParser.normalize(rawInfoHash) ?: throw HttpDeniedException()
        if (rawInfoHash != infoHash) {
            throw HttpDeniedException()
        }
        return YtsDetailsToken(
            movieId = movieId,
            infoHash = infoHash,
        )
    }

    /** 校验 API 页面 URL 路由且不允许已有 query 或 fragment。 */
    private fun requireMoviePage(
        /** API 返回的未信任页面 URL。 */
        pageUrl: String,
    ): okhttp3.HttpUrl {
        /** parsed 先执行 HTTPS、host、端口与 userinfo 检查。 */
        val parsed = pagePolicy.requireHttpsUrl(pageUrl)
        /** rawPath 保证输入本身就是精确两段而非规范化结果。 */
        val rawPath = runCatching { URI(pageUrl).rawPath }.getOrNull() ?: throw HttpDeniedException()
        /** rawSegments 用于拒绝额外段、空 slug 与点段。 */
        val rawSegments = rawPath.removePrefix("/").split('/')
        if (
            rawSegments.size != 2 ||
            rawSegments[0] != "movies" ||
            rawSegments[1].isBlank() ||
            parsed.pathSegments.size != 2 ||
            parsed.pathSegments[0] != "movies" ||
            parsed.pathSegments[1].isBlank() ||
            parsed.query != null ||
            parsed.fragment != null
        ) {
            throw HttpDeniedException()
        }
        return parsed
    }
}

/** Yts 详情 API 的本地稳定身份，不参与序列化或反射。 */
internal data class YtsDetailsToken(
    /** 正整数电影 ID，必须与详情响应一致。 */
    val movieId: Long,
    /** 规范化 infoHash，必须命中详情 torrent。 */
    val infoHash: String,
)
