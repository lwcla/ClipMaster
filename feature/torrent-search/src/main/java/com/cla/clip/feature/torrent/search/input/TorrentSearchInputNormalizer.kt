package com.cla.clip.feature.torrent.search.input

/** 系统入口规范化后的搜索输入，只服务页面流程，不作为持久化或序列化模型。 */
internal data class NormalizedTorrentSearchInput(
    /** 删除控制字符并限制长度后的页面预填文本。 */
    val query: String,
    /** 是否允许入口直接触发搜索；空白或超限输入必须为 false。 */
    val canAutoSearch: Boolean,
    /** 原始安全文本是否超过长度上限，页面可据此提示只预填未自动搜索。 */
    val wasTruncated: Boolean,
)

/** 统一处理宿主入口和 Android 隐式 Intent 的搜索文本边界。 */
internal object TorrentSearchInputNormalizer {
    /** 页面与历史协议允许的最大 Kotlin 字符数。 */
    private const val MAX_QUERY_LENGTH = 512

    /** 删除控制字符、裁剪空白并限制长度，同时保留是否允许自动搜索的决策。 */
    fun normalize(
        /** 来自宿主或系统 Intent 的不可信原始文本。 */
        rawQuery: String?,
    ): NormalizedTorrentSearchInput {
        /** sanitizedQuery 先去除控制字符和边界空白，再参与长度与空值判断。 */
        val sanitizedQuery = sanitizeTorrentSearchText(rawQuery.orEmpty())
        /** wasTruncated 标记安全文本是否超过固定 512 字符边界。 */
        val wasTruncated = sanitizedQuery.length > MAX_QUERY_LENGTH
        /** normalizedQuery 只保留页面和后续搜索链路允许消费的前 512 个字符。 */
        val normalizedQuery = sanitizedQuery.take(MAX_QUERY_LENGTH)
        return NormalizedTorrentSearchInput(
            query = normalizedQuery,
            canAutoSearch = normalizedQuery.isNotBlank() && !wasTruncated,
            wasTruncated = wasTruncated,
        )
    }
}

/** 删除 Unicode 控制字符并裁剪边界空白，不执行 URL 编码或长度截断。 */
internal fun sanitizeTorrentSearchText(
    /** 尚未信任的原始搜索文本。 */
    rawText: String,
): String = buildString(rawText.length) {
    /** 逐字符判断覆盖 CR、LF、NUL 与其他控制类别，同时保留普通 Unicode。 */
    rawText.forEach {
        /** character 是当前待校验字符，控制字符不会进入结果。 */
        character -> if (!character.isISOControl()) append(character)
    }
}.trim()
