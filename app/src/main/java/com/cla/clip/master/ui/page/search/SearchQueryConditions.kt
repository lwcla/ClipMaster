package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.search.ClipSearchTarget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 分页查询身份，不包含菜单勾选模式，避免等价全来源切换重新创建 Pager。 */
internal data class SearchQueryConditions(
    /** 当前搜索关键词。 */
    val query: String,
    /** 时间单选条件。 */
    val timeFilter: SearchTimeFilter,
    /** 有效来源限制；空集合查询全部，包含空字符串则查询未知来源。 */
    val sourceAppPackages: Set<String>,
    /** 定向搜索也属于分页身份，不能沿用其他字段的结果。 */
    val target: ClipSearchTarget = ClipSearchTarget.All,
)

/** 将 UI 状态映射为有效查询并去重；页面未收集时不启动任何数据任务。 */
internal fun Flow<SearchFilterState>.distinctSearchQueryConditions(): Flow<SearchQueryConditions> =
    map { state -> state.toQueryConditions() }.distinctUntilChanged()

/** 页面和状态流共用有效条件映射，All 与显式无勾选具有相同查询身份。 */
internal fun SearchFilterState.toQueryConditions(): SearchQueryConditions =
    SearchQueryConditions(query, timeFilter, sourceAppPackages, target)
