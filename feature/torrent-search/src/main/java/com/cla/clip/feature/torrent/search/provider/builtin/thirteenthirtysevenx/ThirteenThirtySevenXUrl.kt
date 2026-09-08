package com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 1337x 搜索、热门路径与分类映射。 */
internal object ThirteenThirtySevenXUrlBuilder {
    /** manifest 审计通过的主站根地址。 */
    const val BASE_URL = "https://1337x.to"

    /** 构造全部或分类搜索路径，不支持类别回退全部搜索。 */
    fun searchUrl(
        /** 原始搜索词。 */ rawQuery: String,
        /** 调用方分类。 */ category: Category,
    ): String {
        /** query 是去控制字符后的单路径段值。 */
        val query = sanitizeProviderQuery(rawQuery)
        /** categoryName 为空时使用普通 search。 */
        val categoryName = ThirteenThirtySevenXCategoryMapper.toPathName(category)
        /** builder 从固定根地址开始逐段编码。 */
        val builder = BASE_URL.toHttpUrl().newBuilder()
        if (categoryName == null) {
            builder.addPathSegment("search").addPathSegment(query)
        } else {
            builder.addPathSegment("category-search").addPathSegment(query).addPathSegment(categoryName)
        }
        return builder.addPathSegment("1").addPathSegment("").build().toString()
    }

    /** 构造不带尾斜杠的固定热门地址。 */
    fun topUrl(): String = BASE_URL.toHttpUrl().newBuilder().addPathSegment("top-100").build().toString()
}

/** 1337x 请求路径、列表 ID 与详情文本分类映射。 */
internal object ThirteenThirtySevenXCategoryMapper {
    /** ALL 与不支持的 BOOKS 回退普通搜索。 */
    fun toPathName(
        /** 领域分类。 */ category: Category,
    ): String? = when (category) {
        Category.ALL, Category.BOOKS -> null
        Category.ANIME -> "Anime"
        Category.APPS -> "Apps"
        Category.GAMES -> "Games"
        Category.MOVIES -> "Movies"
        Category.MUSIC -> "Music"
        Category.OTHER -> "Other"
        Category.PORN -> "XXX"
        Category.SERIES -> "TV"
    }

    /** Jackett 固定 ID 映射；未知值归 OTHER。 */
    fun fromCategoryId(
        /** `/sub/{id}/...` 中的十进制字符串。 */ id: String,
    ): Category = when (id) {
        "28", "78", "79", "80", "81" -> Category.ANIME
        "22", "23", "24", "25", "26", "27", "53", "58", "59", "60", "68", "69" -> Category.MUSIC
        "1", "2", "3", "4", "42", "54", "55", "66", "70", "73", "76" -> Category.MOVIES
        "5", "6", "7", "9", "41", "71", "74", "75" -> Category.SERIES
        "18", "19", "20", "21", "56", "57" -> Category.APPS
        "10", "11", "12", "13", "14", "15", "16", "17", "43", "44", "45", "46", "72", "77", "82" -> Category.GAMES
        "48", "49", "50", "51", "67" -> Category.PORN
        else -> Category.OTHER
    }

    /** 详情显示文本映射；未知值归 OTHER。 */
    fun fromPageText(
        /** 页面分类文本。 */ rawCategory: String,
    ): Category = when (rawCategory.trim()) {
        "Anime" -> Category.ANIME
        "Apps" -> Category.APPS
        "Games" -> Category.GAMES
        "Movies" -> Category.MOVIES
        "Music" -> Category.MUSIC
        "XXX" -> Category.PORN
        "TV", "Documentaries" -> Category.SERIES
        else -> Category.OTHER
    }
}
