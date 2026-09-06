package com.cla.clip.feature.torrent.search.provider.builtin.dmhy

import com.cla.clip.feature.torrent.search.model.contract.Category

/** Dmhy 分类请求与响应映射；站点子分类不作为持久协议保存。 */
internal object DmhyCategoryMapper {
    /** 不支持的请求分类回退站点全部分类 0。 */
    fun requestId(category: Category): String = when (category) {
        Category.ANIME -> "2"
        Category.BOOKS -> "3"
        Category.GAMES -> "9"
        Category.MUSIC -> "4"
        Category.SERIES -> "6"
        Category.OTHER -> "1"
        else -> "0"
    }

    /** 按固定上源归并子分类，未知或缺失 ID 映射其他。 */
    fun fromId(id: String?): Category = when (id) {
        "2", "7", "31" -> Category.ANIME
        "3" -> Category.BOOKS
        "41", "42", "6" -> Category.SERIES
        "4", "43", "44", "15" -> Category.MUSIC
        "9", "17", "18", "19", "20", "21" -> Category.GAMES
        else -> Category.OTHER
    }
}
