package com.cla.clip.master.ui.page.search

import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.base.general.entity.ClipVisibilityScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow

/** 分页缓存身份：条件、普通/折叠范围与显式重试共同决定新会话。 */
internal data class SearchResultKey(
    /** 不包含菜单显示状态的有效业务条件。 */ val conditions: SearchQueryConditions,
    /** 数据范围独立于搜索字段目标。 */ val visibility: ClipVisibilityScope,
    /** 重试失败的来源读取也需要新建分页会话。 */ val retry: Int,
)

/** 仅保留当前查询的缓存；同一回栈查询复用，条件改变必须取消旧查询，防止高亮串代。 */
internal class SearchPagingSessions(
    /** 随 ViewModel 销毁取消所有缓存任务。 */ private val parentScope: CoroutineScope,
) {
    /** 当前唯一会话，不能缓存用户历史查询集合。 */
    private var current: Session? = null

    /** 主线程调用：相同身份复用，切换身份先取消旧缓存，避免后台继续观察已经过时的查询。 */
    fun get(
        /** 页面和结果高亮共同使用的查询身份。 */ key: SearchResultKey,
        /** 仅在首次收集时执行数据读取，缓存由本类统一管理。 */ create: () -> Flow<PagingData<ClipShowEntity>>,
    ): Flow<PagingData<ClipShowEntity>> {
        current?.takeIf { it.key == key }?.let { return it.data }
        current?.scope?.cancel()
        /** 独立子任务用于隔离每次查询的缓存生命周期。 */
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        /** 缓存只服务一个条件，返回详情时可复用已加载页面。 */
        val data = create().cachedIn(scope)
        current = Session(key, scope, data)
        return data
    }

    /** 将缓存、身份与生命周期组合为不可分割状态，避免多份可空字段互相失配。 */
    private data class Session(
        /** 当前缓存所属查询。 */ val key: SearchResultKey,
        /** 替换时需要取消的独立缓存作用域。 */ val scope: CoroutineScope,
        /** 被 Compose 结果 collector 观察的惰性流。 */ val data: Flow<PagingData<ClipShowEntity>>,
    )
}
