package com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay

import com.cla.clip.feature.torrent.search.model.contract.Category

/** ThePirateBay 请求和响应分类的固定上游映射。 */
internal object ThePirateBayCategoryMapper {
    /** 将统一十类映射到 API 请求类别，ANIME 按上游回退全部。 */
    fun requestId(
        /** 调用方选择的统一分类。 */
        category: Category,
    ): Int = when (category) {
        Category.ALL,
        Category.ANIME,
        -> 0
        Category.APPS -> 300
        Category.BOOKS -> 601
        Category.GAMES -> 400
        Category.MOVIES,
        Category.SERIES,
        -> 200
        Category.MUSIC -> 101
        Category.PORN -> 500
        Category.OTHER -> 600
    }

    /** 将外部类别 ID 安全映射到领域分类，未知或溢出值回退 OTHER。 */
    fun resultCategory(
        /** 外部类别 ID 使用 Long，禁止先截断为 Int。 */
        categoryId: Long?,
    ): Category = when (categoryId) {
        in 300L..306L,
        399L,
        -> Category.APPS
        601L -> Category.BOOKS
        in 400L..408L,
        499L,
        -> Category.GAMES
        201L,
        202L,
        204L,
        207L,
        209L,
        210L,
        211L,
        -> Category.MOVIES
        in 100L..104L,
        199L,
        -> Category.MUSIC
        in 500L..507L,
        599L,
        -> Category.PORN
        205L,
        208L,
        212L,
        -> Category.SERIES
        else -> Category.OTHER
    }
}
