package com.cla.clip.master.ui.page.mine

import com.cla.clip.base.general.config.ClipSourceBlockRules
import java.util.Locale

/**
 * 仅展示本机已识别的安装应用，再按搜索、系统应用开关和完整草稿筛选，保留输入顺序。
 *
 * 只返回展示列表，不修改完整草稿或持久设置；页面确认必须仍保存完整草稿。
 */
internal fun filterClipSourceBlockCandidates(
    candidates: List<BlockedSourceAppCandidate>,
    draftPackages: Set<String>,
    searchQuery: String,
    showSystemApps: Boolean,
): List<BlockedSourceAppCandidate> {
    /** 标准化后的搜索关键词；只用于 UI 匹配，不影响保存规则。 */
    val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)
    return candidates.filter { candidate ->
        /** 搜索模式下系统应用也可命中；默认模式下按开关隐藏未选中的系统应用。 */
        val visibleByDefault = candidate.packageName in draftPackages ||
            showSystemApps ||
            candidate.defaultVisible ||
            normalizedQuery.isNotEmpty()
        /** 搜索命中 App 名或包名。 */
        val matchesQuery = normalizedQuery.isEmpty() ||
            candidate.displayName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            candidate.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
        // 安装条件独立于勾选、历史来源和搜索，避免隐藏规则经其他可见性分支重新出现。
        candidate.installed && visibleByDefault && matchesQuery
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
