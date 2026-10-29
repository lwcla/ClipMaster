package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.search.ClipSearchField

/** 搜索页时间单选值，始终有一项；查询时再计算窗口，不保存过期时间戳。 */
enum class SearchTimeFilter {
    /** 不限制时间；它本身也是一个已选中的选项。 */
    ALL,
    /** 当天零点到次日零点。 */
    TODAY,
    /** 最近七天。 */
    LAST_7_DAYS,
    /** 最近三十天。 */
    LAST_30_DAYS,
}

/** 搜索页唯一筛选状态，来源真实勾选与有效查询分开表达；仅当前页面会话使用。 */
data class SearchFilterState(
    /** 输入关键词；现有输入入口负责单行规整。 */
    val query: String = "",
    /** 非空枚举保证时间始终单选，来源事件不能重置它。 */
    val timeFilter: SearchTimeFilter = SearchTimeFilter.ALL,
    /** 即时生效的真实勾选态；默认全选，无勾选同样查询全部来源。 */
    val sourceAppSelection: SourceAppSelection = SourceAppSelection.All,
    /** 关键词匹配字段默认七项全选，仅当前页面实例保留，不进入设置或备份。 */
    val fields: Set<ClipSearchField> = ClipSearchField.entries.toSet(),
) {
    /** Repository 使用的有效包名集合；不是另一个可写状态。 */
    val sourceAppPackages: Set<String>
        get() = sourceAppSelection.queryPackageNames
}
