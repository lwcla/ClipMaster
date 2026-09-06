package com.cla.clip.feature.torrent.search.provider.builtin.knaben

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/** Knaben 固定字段 JSON 请求构造器，不依赖反射序列化模型。 */
internal object KnabenRequestBuilder {
    /** 构造 search/latest/top 共用正文，null query 明确表示浏览入口。 */
    fun build(
        /** 原始用户查询；存在时先删除控制字符并裁剪。 */
        query: String?,
        /** 领域分类；ALL 不输出 categories。 */
        category: Category,
        /** 固定调用方提供的 order_by 值。 */
        orderBy: String,
    ): String = buildJsonObject {
        /** rawQuery 是当前需要清洗后写入正文的用户查询。 */
        query?.let { rawQuery -> put("query", JsonPrimitive(sanitizeProviderQuery(rawQuery))) }
        put("size", JsonPrimitive(300)); put("order_by", JsonPrimitive(orderBy)); put("order_direction", JsonPrimitive("desc"))
        put("hide_unsafe", JsonPrimitive(true)); put("hide_xxx", JsonPrimitive(false))
        /** categoryId 是非 ALL 分类的固定百万级协议值。 */
        val categoryId = categoryId(category)
        if (categoryId != null) putJsonArray("categories") { add(JsonPrimitive(categoryId)) }
    }.toString()

    /** 将十类领域值映射到固定上源类别 ID。 */
    private fun categoryId(
        /** 当前请求分类。 */
        category: Category,
    ): Int? = when (category) {
        Category.ALL -> null; Category.MUSIC -> 1_000_000; Category.SERIES -> 2_000_000; Category.MOVIES -> 3_000_000
        Category.APPS -> 4_000_000; Category.PORN -> 5_000_000; Category.ANIME -> 6_000_000; Category.GAMES -> 7_000_000
        Category.BOOKS -> 9_000_000; Category.OTHER -> 10_000_000
    }
}
