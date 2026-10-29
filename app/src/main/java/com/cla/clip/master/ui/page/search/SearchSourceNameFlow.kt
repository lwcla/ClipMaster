package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.search.ClipSearchTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.paging.LoadStates
import com.cla.clip.base.general.entity.ClipShowEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch

/** 只在非空名称查询时观察投影；同一命中集合不重建分页，读取失败交给页面会话提供显式重试。 */
internal fun searchSourceNamePackages(
    /** 当前分页会话的固定查询条件。 */ conditions: SearchQueryConditions,
    /** 惰性获取名称流，由调用方挂接可见性门控。 */ candidates: () -> Flow<List<SourceAppCandidate>>,
): Flow<Set<String>?> = if (conditions.target == ClipSearchTarget.SourceName && conditions.query.isNotBlank()) {
    candidates().map { values -> matchSearchSourcePackages(conditions.query, values, conditions.sourceAppPackages) }
        .distinctUntilChanged()
} else flowOf(null)

/** 前置名称读取失败转换为 Paging 可显示的错误；不携带原异常/绑定值，取消继续向父任务传播。 */
internal fun Flow<PagingData<ClipShowEntity>>.catchSearchLoadFailure(): Flow<PagingData<ClipShowEntity>> = catch { error ->
    if (error is CancellationException) throw error
    emit(PagingData.empty(sourceLoadStates = LoadStates(
        refresh = LoadState.Error(SearchSourceNameReadException()),
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = true),
    )))
}
