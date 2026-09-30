package com.cla.clip.master.ui.page.mine

import com.cla.clip.base.general.config.ClipSourceBlockRules
import java.util.Locale

/**
 * 对当前扫描候选应用搜索和系统应用开关，保留已保存屏蔽优先的输入顺序。
 *
 * 只返回展示列表，不修改完整草稿或持久设置；页面确认必须仍保存完整草稿。
 */
internal fun filterClipSourceBlockCandidates(
    candidates: List<BlockedSourceAppCandidate>,
    searchQuery: String,
    showSystemApps: Boolean,
): List<BlockedSourceAppCandidate> {
    /** 标准化后的搜索关键词；只用于 UI 匹配，不影响保存规则。 */
    val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)
    return candidates.filter { candidate ->
        /** 系统应用只由开关放行；已保存、草稿勾选和搜索都不改变分类显隐。 */
        val visibleBySystemSetting = showSystemApps || !candidate.systemApp
        /** 搜索命中 App 名或包名。 */
        val matchesQuery = normalizedQuery.isEmpty() ||
            candidate.appName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            candidate.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        visibleBySystemSetting && matchesQuery
    }
}

/**
 * 切换一个可见候选包名的勾选，返回完整草稿，保留其他隐藏规则。
 *
 * 包名来自已构建候选；移除优先于容量判断，达到完整名单上限时新增操作返回原集合。
 * 本方法不修改输入、不做持久化，规范化仍由保存入口统一处理。
 */
internal fun toggleBlockedSourcePackage(draftPackages: Set<String>, packageName: String): Set<String> {
    return if (packageName in draftPackages) {
        draftPackages - packageName
    } else if (draftPackages.size < ClipSourceBlockRules.MAX_PACKAGE_COUNT) {
        draftPackages + packageName
    } else {
        // 隐藏包名同样占用规则容量，达到上限时不能通过编辑可见项绕过限制。
        draftPackages
    }
}
