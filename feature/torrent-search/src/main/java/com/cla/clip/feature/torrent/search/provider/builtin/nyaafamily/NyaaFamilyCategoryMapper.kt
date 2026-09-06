package com.cla.clip.feature.torrent.search.provider.builtin.nyaafamily

import com.cla.clip.feature.torrent.search.model.contract.Category

/** Nyaa 族网页类别到领域类别的稳定策略。 */
internal fun interface NyaaFamilyCategoryMapper {
    /** 将结构化类别 query 值映射为领域分类。 */
    fun map(/** 站点类别 ID。 */ categoryId: String?): Category
}
