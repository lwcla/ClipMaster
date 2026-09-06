package com.cla.clip.feature.torrent.search.provider.builtin.internetarchive

import com.cla.clip.feature.torrent.search.model.contract.Category

/** IA mediatype 的单向请求/响应映射，未知值保持固定上源 OTHER 语义。 */
internal object InternetArchiveCategoryMapper {
    /** 请求不支持的分类回退 other；ALL 由调用方省略过滤表达式。 */
    fun requestType(
        /** 调用方选择的分类，映射策略遵循固定上源。 */
        category: Category,
    ): String = when (category) {
        Category.APPS -> "software"
        Category.BOOKS -> "texts"
        Category.MOVIES -> "movies"
        else -> "other"
    }

    /** 外部 mediatype 按固定协议值读取，不用 Kotlin 枚举名充当协议。 */
    fun category(
        /** 外部 mediatype 协议值，未知值保守映射 OTHER。 */
        mediaType: String,
    ): Category = when (mediaType) {
        "software" -> Category.APPS
        "texts" -> Category.BOOKS
        "movies" -> Category.MOVIES
        else -> Category.OTHER
    }
}
