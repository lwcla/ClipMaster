package com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan

import com.cla.clip.feature.torrent.search.model.contract.Category

/** TokyoToshokan 请求编号与页面编号的单一映射入口。 */
internal object TokyoToshokanCategoryMapper {
    /** 十类搜索完整映射；无原生搜索分类时回退全部。 */
    fun requestId(
        /** 当前领域分类。 */ category: Category,
    ): String = when (category) {
        Category.ANIME -> "1"
        Category.BOOKS -> "3"
        Category.MUSIC -> "2"
        Category.PORN -> "15"
        Category.OTHER -> "5"
        else -> "0"
    }

    /** 首页浏览只为受支持的非 ALL 分类返回编号。 */
    fun browseId(
        /** 当前领域分类。 */ category: Category,
    ): String? = when (category) {
        Category.ANIME -> "1"
        Category.BOOKS -> "3"
        Category.MUSIC -> "2"
        Category.PORN -> "15"
        Category.OTHER -> "5"
        else -> null
    }

    /** 页面 cat 编号按固定上游分组；未知编号保持缺失语义。 */
    fun fromPageId(
        /** 页面 cat 查询值。 */ id: String,
    ): Category? = when (id) {
        "1", "7", "8", "10", "11" -> Category.ANIME
        "3" -> Category.BOOKS
        "2", "9" -> Category.MUSIC
        "4", "12", "13", "14", "15" -> Category.PORN
        "5" -> Category.OTHER
        else -> null
    }
}
