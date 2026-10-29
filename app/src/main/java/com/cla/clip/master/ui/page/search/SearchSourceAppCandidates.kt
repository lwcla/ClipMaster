package com.cla.clip.master.ui.page.search

import com.cla.clip.base.general.dao.data.SourceAppCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.catch

/** 菜单候选失败不终止 ViewModel；重试由外部代次重订阅，结果流独立呈现错误，不吞掉取消。 */
internal fun searchMenuCandidates(
    /** 惰性重试读取来源投影。 */ read: () -> Flow<List<SourceAppCandidate>>,
): Flow<List<SourceAppCandidate>> = flow { emitAll(read()) }.catch { error ->
    if (error is CancellationException) throw error
    // 菜单只降级为空候选，不改变用户勾选；不打印原始数据库异常或名单。
    emit(emptyList())
}
