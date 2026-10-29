package com.cla.clip.master.ui.page.search

import androidx.paging.PagingData
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.search.ClipSearchField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 真实 cachedIn 会话隔离，防止旧结果带新关键词或新字段样式。 */
class SearchPagingSessionsTest {
    /** 只有相同查询复用缓存，字段切换或重试都会产生新缓存并取消旧作用域。 */
    @Test fun changingFieldOrRetryReplacesAndCancelsPreviousSession() = runBlocking {
        /** 父任务保证测试结束不遗留 cachedIn 协程。 */
        val parent = SupervisorJob(coroutineContext[Job])
        /** 使用可控父作用域，不需要 Android ViewModel。 */
        val scope = CoroutineScope(coroutineContext + parent)
        /** 被测唯一查询缓存。 */
        val sessions = SearchPagingSessions(scope)
        /** 初始综合搜索身份。 */
        val firstKey = SearchResultKey(SearchQueryConditions("会议", SearchTimeFilter.ALL, emptySet()), ClipVisibilityScope.VisibleOnly, 0)
        try {
            /** 首次查询流。 */
            val first = sessions.get(firstKey) { flowOf(PagingData.empty()) }
            /** 当前缓存对应的唯一子任务。 */
            val firstJob = parent.children.single()
            assertSame(first, sessions.get(firstKey) { error("相同查询不应重建") })
            /** 只有字段发生变化的第二次查询。 */
            val secondKey = firstKey.copy(conditions = firstKey.conditions.copy(fields = setOf(ClipSearchField.LinkTitle)))
            /** 第二次查询必须是独立的分页数据流。 */
            val second = sessions.get(secondKey) { flowOf(PagingData.empty()) }
            assertNotSame(first, second)
            assertTrue(firstJob.isCancelled)
            assertNotSame(second, sessions.get(secondKey.copy(retry = 1)) { flowOf(PagingData.empty()) })
        } finally { scope.cancel() }
    }
}
