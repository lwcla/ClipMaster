package com.cla.clip.feature.torrent.search.provider.builtin.torrentz

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Torrentz 搜索、最新、热门 URL 与数字分类映射。 */
internal object TorrentzUrlBuilder {
    /** manifest 审计通过的固定主站根地址。 */
    const val BASE_URL = "https://torrentz2.nz"

    /** 搜索查询结构化编码，可选类别使用固定数字 ID。 */
    fun searchUrl(
        /** 原始搜索词。 */ rawQuery: String,
        /** 调用方分类。 */ category: Category,
    ): String {
        /** query 删除控制字符并裁剪边界空白。 */
        val query = sanitizeProviderQuery(rawQuery)
        /** builder 先添加必需查询，再按类别补充 category。 */
        val builder = BASE_URL.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("q", query)
        return builder.addOptionalCategory(category).build().toString()
    }

    /** 最新页只在非 ALL 且受支持时附加类别。 */
    fun latestUrl(
        /** 浏览分类。 */ category: Category,
    ): String = BASE_URL.toHttpUrl().newBuilder().addPathSegment("latest")
        .addOptionalCategory(category).build().toString()

    /** 热门页只在非 ALL 且受支持时附加类别。 */
    fun topUrl(
        /** 浏览分类。 */ category: Category,
    ): String = BASE_URL.toHttpUrl().newBuilder().addPathSegment("trending")
        .addOptionalCategory(category).build().toString()

    /** 在存在固定数字映射时添加 category 查询参数。 */
    private fun HttpUrl.Builder.addOptionalCategory(
        /** 调用方分类。 */ category: Category,
    ): HttpUrl.Builder {
        /** categoryId 为空表示 ALL 或未来未支持类别。 */
        val categoryId = TorrentzCategoryMapper.toId(category)
        if (categoryId != null) addQueryParameter("category", categoryId.toString())
        return this
    }
}

/** Torrentz 数字分类双向映射。 */
internal object TorrentzCategoryMapper {
    /** ALL 没有查询参数，其他固定九类使用上游 ID。 */
    fun toId(
        /** 领域分类。 */ category: Category,
    ): Int? = when (category) {
        Category.ALL -> null
        Category.OTHER -> 1
        Category.MOVIES -> 2
        Category.SERIES -> 3
        Category.ANIME -> 4
        Category.APPS -> 5
        Category.GAMES -> 6
        Category.MUSIC -> 7
        Category.BOOKS -> 9
        Category.PORN -> 10
    }

    /** 未知页面 ID 归 OTHER。 */
    fun fromId(
        /** 页面十进制类别 ID。 */ id: Int,
    ): Category = when (id) {
        2 -> Category.MOVIES
        3 -> Category.SERIES
        4 -> Category.ANIME
        5 -> Category.APPS
        6 -> Category.GAMES
        7 -> Category.MUSIC
        9 -> Category.BOOKS
        10 -> Category.PORN
        else -> Category.OTHER
    }
}
