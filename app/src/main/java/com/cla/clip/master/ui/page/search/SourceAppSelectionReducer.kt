package com.cla.clip.master.ui.page.search

/** 即时来源选择事件的纯归约器；只改变来源，不接收时间或产生 IO 副作用。 */
internal object SourceAppSelectionReducer {
    /** 候选暂空时忽略操作；已全选则全部取消，部分/空选择则全选，均保持时间隔离。 */
    fun toggleAll(selection: SourceAppSelection, candidates: Set<String>): SourceAppSelection {
        if (candidates.isEmpty()) return selection
        return if (selection.isAllSelected) SourceAppSelection.Explicit(emptySet()) else SourceAppSelection.All
    }

    /** 只切换仍存在的候选；All 先展开，空集合保持未勾选，选齐且无缺失候选才归一。 */
    fun toggleItem(selection: SourceAppSelection, candidates: Set<String>, packageName: String): SourceAppSelection {
        // 过滤候选刷新后的迟到点击，空字符串在候选内时仍是合法身份。
        if (packageName !in candidates) return selection
        /** All 展开当前候选；显式选择保留不在当前候选中的旧身份。 */
        val selected = when (selection) {
            SourceAppSelection.All -> candidates
            is SourceAppSelection.Explicit -> selection.packageNames
        }
        /** 本次立即应用的勾选集合，取消最后一项不自动重新勾选。 */
        val next = if (packageName in selected) selected - packageName else selected + packageName
        return if (next.isNotEmpty() && next == candidates) SourceAppSelection.All else SourceAppSelection.Explicit(next)
    }
}
