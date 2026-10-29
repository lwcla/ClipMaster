package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.search.ClipSearchField
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 保护名称观察仅服务有效名称搜索，图标/同集合名称变动不重复创建 Pager。 */
class SearchSourceNameFlowTest {
    /** 从无匹配到匹配必须重新查询，相同包名集合的投影变化应去重。 */
    @Test fun onlyChangedMatchSetEmits() = runBlocking {
        /** 只在真实名称含关键字时匹配。 */
        val conditions = SearchQueryConditions("Chat", SearchTimeFilter.ALL, emptySet(), setOf(ClipSearchField.SourceName))
        /** 模拟初始无命中、名称改为命中、名称继续变化但命中集合不变。 */
        val sets = searchSourceNamePackages(conditions) { flowOf(listOf(SourceAppCandidate("a", "Other")),
            listOf(SourceAppCandidate("a", "Chat")), listOf(SourceAppCandidate("a", "Chat Work"))) }.toList()
        assertEquals(listOf(emptySet<String>(), setOf("a")), sets)
    }

    /** 非名称目标与空词不应为了统计或匹配启动名称查询。 */
    @Test fun nonNameAndEmptyQueryDoNotObserveCandidates() = runBlocking {
        /** 内容范围不需要来源名称读取。 */
        val content = SearchQueryConditions("Chat", SearchTimeFilter.ALL, emptySet(), setOf(ClipSearchField.Content))
        assertEquals(listOf(null), searchSourceNamePackages(content) { error("不应读取") }.toList())
        assertEquals(listOf(null), searchSourceNamePackages(content.copy(query = " ", fields = setOf(ClipSearchField.SourceName))) { error("不应读取") }.toList())
        assertEquals(listOf(null), searchSourceNamePackages(content.copy(fields = setOf(ClipSearchField.SourcePackage))) { error("包名不应读取名称") }.toList())
    }

    /** 七项全选与部分包含名称时都要解析名称，空集合必须保留给 OR 分支而非整页短路。 */
    @Test fun combinedFieldsStillResolveOnlyTheNameBranch() = runBlocking {
        /** 同时选择内容和名称，不要求两字段同时命中。 */
        val conditions = SearchQueryConditions("needle", SearchTimeFilter.ALL, emptySet(), setOf(ClipSearchField.Content, ClipSearchField.SourceName))
        assertEquals(listOf(emptySet<String>()), searchSourceNamePackages(conditions) { flowOf(emptyList()) }.toList())
        assertEquals(listOf(setOf("fixture")), searchSourceNamePackages(conditions.copy(fields = ClipSearchField.entries.toSet())) {
            flowOf(listOf(SourceAppCandidate("fixture", "a needle b")))
        }.toList())
    }
}
