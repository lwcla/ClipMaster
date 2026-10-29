package com.cla.clip.master.ui.page.search

import androidx.paging.*
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import com.cla.clip.base.general.search.ClipSearchField
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** 消费真实 PagingData/PagingSource，验证缓存回放与错误恢复，而非只比较 Flow 引用。 */
class SearchPagingConsumptionTest {
    /** 返回同查询不重复加载，切换字段后只能呈现新查询条目。 */
    @Test fun returningReplaysLoadedItemsAndSwitchingFieldNeverReplaysOldItems() = runBlocking {
        /** 独立缓存父作用域保证测试可回收。 */
        val cacheScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        /** 使用生产会话容器与真正 Pager。 */
        val sessions = SearchPagingSessions(cacheScope)
        /** 首次查询的固定身份。 */
        val key = key()
        /** 实际 PagingSource.load 次数，回栈不能增加。 */
        var loads = 0
        try {
            /** 首次两条合成记录由真实分页加载交付。 */
            val first = sessions.get(key) { pager(listOf(11, 12)) { loads++ } }
            assertEquals(listOf(11L, 12L), readIds(first))
            assertEquals(1, loads)
            assertEquals(listOf(11L, 12L), readIds(sessions.get(key) { error("回栈不应重建") }))
            assertEquals(1, loads)
            /** 改变字段使用不同的数据，捕获缓存串代。 */
            val next = sessions.get(key.copy(conditions = key.conditions.copy(fields = setOf(ClipSearchField.LinkTitle)))) {
                pager(listOf(21)) { loads++ }
            }
            assertEquals(listOf(21L), readIds(next))
            assertEquals(2, loads)
        } finally { cacheScope.cancel() }
    }

    /** 名称读取失败变成实际 refresh Error，显式重试后可呈现非空数据。 */
    @Test fun retryAfterNameFailurePresentsRecoveredItems() = runBlocking {
        /** 与页面相同的独立缓存生命周期。 */
        val cacheScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        /** 真正生产会话缓存，不 mock cachedIn。 */
        val sessions = SearchPagingSessions(cacheScope)
        /** 名称模式保证经过来源匹配与失败转换链。 */
        val key = key().copy(conditions = key().conditions.copy(fields = setOf(ClipSearchField.SourceName)))
        /** 数据源尝试次数，显式重试才允许第二次读取。 */
        var attempts = 0
        /** 页面重试使用同一工厂；第一次失败、第二次恢复只改变数据源行为。 */
        val create = {
            searchSourceNamePackages(key.conditions) { flow {
                attempts++
                if (attempts == 1) error("fixture name failure")
                emit(listOf(SourceAppCandidate("fixture", "会议")))
            } }.map { matches -> PagingData.from(if (matches.orEmpty().isNotEmpty()) listOf(clip(31)) else emptyList()) }
                .catchSearchLoadFailure()
        }
        try {
            /** 无 Android 依赖的真实 Presenter，错误状态来自生产 PagingData。 */
            val presenter = object : PagingDataPresenter<ClipShowEntity>(Dispatchers.Unconfined) {
                /** 失败首屏无需处理数据事件，通过 LoadState 验证。 */
                override suspend fun presentPagingDataEvent(event: PagingDataEvent<ClipShowEntity>) = Unit
            }
            /** 首次 collector 模拟页面结果观察。 */
            val first = launch { sessions.get(key, create).collectLatest { presenter.collectFrom(it) } }
            try {
                /** 确认错误确实能被 UI 观察，不把抛异常或空结果当成错误呈现。 */
                val state = withTimeout(5_000) { presenter.loadStateFlow.filterNotNull().first { it.refresh is LoadState.Error } }
                assertTrue((state.refresh as LoadState.Error).error is SearchSourceNameReadException)
                assertTrue(presenter.snapshot().items.isEmpty())
            } finally { first.cancelAndJoin() }
            assertEquals(1, attempts)
            assertEquals(listOf(31L), readIds(sessions.get(key.copy(retry = 1), create)))
            assertEquals(2, attempts)
        } finally { cacheScope.cancel() }
    }

    /** 只提供最小固定条件，关键词对应上面合成来源名。 */
    private fun key() = SearchResultKey(SearchQueryConditions("会议", SearchTimeFilter.ALL, emptySet()), ClipVisibilityScope.VisibleOnly, 0)

    /** 可控数据源负责真正 load，回调只记录加载次数而不模拟结果校验。 */
    private fun pager(
        /** 本次查询独有的条目身份。 */ ids: List<Long>,
        /** 记录每次实际加载，验证回放不重复 IO。 */ onLoad: () -> Unit,
    ): Flow<PagingData<ClipShowEntity>> = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
        object : PagingSource<Int, ClipShowEntity>() {
            /** 当前测试一页完成，不需要恢复锚点。 */
            override fun getRefreshKey(state: PagingState<Int, ClipShowEntity>): Int? = null
            /** 合成首屏没有前后页，数据由测试手工指定。 */
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ClipShowEntity> {
                onLoad()
                return LoadResult.Page(ids.map { clip(it) }, null, null)
            }
        }
    }.flow

    /** 每次创建新 presenter 模拟回栈，避免读取前一个 collector 残留快照。 */
    private suspend fun CoroutineScope.readIds(/** 要真实消费的分页流。 */ pages: Flow<PagingData<ClipShowEntity>>): List<Long> {
        /** 首次非空快照到达信号。 */
        val received = CompletableDeferred<List<Long>>()
        /** 独立 UI 消费者，不使用 Android Main Dispatcher。 */
        val presenter = object : PagingDataPresenter<ClipShowEntity>(Dispatchers.Unconfined) {
            /** 只有真实数据事件到达后才返回断言对象。 */
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ClipShowEntity>) {
                if (snapshot().items.isNotEmpty()) received.complete(snapshot().items.map { it.id })
            }
        }
        /** 模拟 Compose 收集与离开页面取消。 */
        val collecting = launch { pages.collectLatest { presenter.collectFrom(it) } }
        return try { withTimeout(5_000) { received.await() } } finally { collecting.cancelAndJoin() }
    }

    /** 合成展示实体绕过 Android 时间格式化，所有身份由测试显式给出。 */
    private fun clip(/** 稳定条目 id。 */ id: Long) = ClipShowEntity(id, "fixture-$id", 0, 0, 0,
        "", "fixture", false, false, null, null, null, "会议")
}
