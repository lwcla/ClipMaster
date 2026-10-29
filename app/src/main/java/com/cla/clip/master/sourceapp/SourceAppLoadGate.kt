package com.cla.clip.master.sourceapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest

/** 页面级历史名称读取门控；暂停未完成查库，不清空 cachedIn 已交付的轻量正文。 */
internal class SourceAppLoadGate {
    /** 聚合可见绑定，避免一个入口退出误关另一个入口。 */
    private val owners = mutableSetOf<Any>()
    /** 未有可见页面时不启动读取，也不在 ViewModel 初始化时产生 IO。 */
    private val active = MutableStateFlow(false)

    /** UI 生命周期绑定更新引用；同步锁只保护轻量集合，不执行装配。 */
    fun setActive(owner: Any, visible: Boolean) = synchronized(owners) {
        if (visible) owners.add(owner) else owners.remove(owner)
        active.value = owners.isNotEmpty()
    }

    /** 连续名称投影观察同样服从页面可见性；隐藏取消订阅，恢复重建，不持有后台数据库观察。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(/** 每次重新可见时重新建立观察，隐藏取消数据库订阅。 */ read: () -> Flow<T>): Flow<T> =
        active.flatMapLatest { visible -> if (visible) read() else emptyFlow() }

    /** 两态均参与 latest：隐藏取消当前子读取，恢复重试；父任务取消仍直接传播。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> run(read: suspend () -> T): T = active.transformLatest { visible ->
        if (visible) emit(read())
    }.first()
}
