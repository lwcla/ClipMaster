package com.cla.clip.master.ui.page.mine

import java.util.Locale

/**
 * 对当前扫描候选应用搜索和系统应用开关，保留已保存屏蔽优先的输入顺序。
 *
 * 只返回展示列表，不修改完整已提交名单；单项增删由仓库保留其他隐藏规则。
 */
internal fun filterClipSourceBlockCandidates(
    candidates: List<BlockedSourceAppCandidate>,
    searchQuery: String,
    showSystemApps: Boolean,
): List<BlockedSourceAppCandidate> {
    /** 标准化后的搜索关键词；只用于 UI 匹配，不影响保存规则。 */
    val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)
    return candidates.filter { candidate ->
        /** 系统应用只由开关放行；已保存状态和搜索都不改变分类显隐。 */
        val visibleBySystemSetting = showSystemApps || !candidate.systemApp
        /** 搜索命中 App 名或包名。 */
        val matchesQuery = normalizedQuery.isEmpty() ||
            candidate.appName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            candidate.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        visibleBySystemSetting && matchesQuery
    }
}
