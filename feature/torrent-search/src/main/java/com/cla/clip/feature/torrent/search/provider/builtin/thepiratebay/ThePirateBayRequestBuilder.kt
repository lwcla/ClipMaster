package com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** ThePirateBay 固定页面与 API 地址构造器，禁止备用页面 host 进入 API。 */
internal object ThePirateBayRequestBuilder {
    /** 公开展示页根地址，也是结果详情 token 的唯一 host。 */
    const val PAGE_BASE_URL = "https://thepiratebay.org"

    /** 唯一 API 根地址。 */
    private val apiBaseUrl = "https://apibay.org".toHttpUrl()

    /** 构造搜索端点并由 HttpUrl 编码 query 与类别。 */
    fun searchUrl(
        /** 原始查询先删除控制字符。 */
        query: String,
        /** 统一分类映射到固定整数 ID。 */
        category: Category,
    ): String = apiBaseUrl.newBuilder()
        .addPathSegment("q.php")
        .addQueryParameter("q", sanitizeProviderQuery(query))
        .addQueryParameter("cat", ThePirateBayCategoryMapper.requestId(category).toString())
        .build()
        .toString()

    /** 构造 latest 入口；ALL 使用固定 recent 文件。 */
    fun latestUrl(
        /** 当前浏览分类。 */
        category: Category,
    ): String = if (category == Category.ALL) {
        apiBaseUrl.newBuilder()
            .addPathSegments("precompiled/data_top100_recent.json")
            .build()
            .toString()
    } else {
        apiBaseUrl.newBuilder()
            .addPathSegment("q.php")
            .addQueryParameter("q", "category:${ThePirateBayCategoryMapper.requestId(category)}")
            .build()
            .toString()
    }

    /** 构造 top 预编译文件，分类后缀完整保留固定上游规则。 */
    fun topUrl(
        /** 当前浏览分类。 */
        category: Category,
    ): String {
        /** categorySuffix 在 ALL 时为空，其他分类包含固定 ID。 */
        val categorySuffix = if (category == Category.ALL) {
            ""
        } else {
            "_${ThePirateBayCategoryMapper.requestId(category)}"
        }
        return apiBaseUrl.newBuilder()
            .addPathSegments("precompiled/data_top100_48h$categorySuffix.json")
            .build()
            .toString()
    }

    /** 构造详情 API URL，调用方只能传入已验证正整数。 */
    fun detailsUrl(
        /** 页面 token 提取出的稳定数字 ID。 */
        torrentId: Long,
    ): String {
        if (torrentId <= 0L) {
            throw HttpDeniedException()
        }
        return apiBaseUrl.newBuilder()
            .addPathSegment("t.php")
            .addQueryParameter("id", torrentId.toString())
            .build()
            .toString()
    }

    /** 为搜索结果构造唯一页面 token；非法 ID 仅跳过该候选。 */
    fun detailsPageUrl(
        /** 外部结果对象提供的字符串 ID。 */
        rawTorrentId: String,
    ): String? {
        /** torrentId 必须是可表示的正整数且保持十进制规范。 */
        val torrentId = rawTorrentId.toLongOrNull()?.takeIf { value -> value > 0L } ?: return null
        if (rawTorrentId != torrentId.toString()) {
            return null
        }
        return PAGE_BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("description.php")
            .addQueryParameter("id", torrentId.toString())
            .build()
            .toString()
    }
}
