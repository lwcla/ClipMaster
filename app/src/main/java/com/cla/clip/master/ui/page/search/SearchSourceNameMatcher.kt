package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.dao.data.SourceAppCandidate

/** 只按真实名称匹配并与来源限制取交集；空结果必须保留为空，不能解释成全部来源。 */
internal fun matchSearchSourcePackages(
    /** 去首尾空白后作为完整短语匹配。 */ query: String,
    /** 已记录的轻量包名/名称投影，不读取图标。 */ candidates: List<SourceAppCandidate>,
    /** 空集合表示用户没有限制来源。 */ selectedPackages: Set<String>,
): Set<String> {
    /** 完整短语沿用输入中的内部空白，符号均按字面量处理。 */
    val keyword = query.trim()
    if (keyword.isEmpty()) return emptySet()
    return candidates.asSequence()
        .filter { candidate -> candidate.appName.isNotBlank() && candidate.appName.contains(keyword, ignoreCase = true) }
        .map { candidate -> candidate.packageName }
        .filter { packageName -> selectedPackages.isEmpty() || packageName in selectedPackages }
        .toSet()
}
