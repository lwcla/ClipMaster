package com.cla.clip.feature.torrent.search.provider.builtin.common

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Provider JSON/DOM 大列表使用的协作式可取消映射；每个元素前检查父任务，
 * `null` 结果按单条缺字段容错跳过。
 */
internal suspend fun <Input, Output : Any> Iterable<Input>.mapNotNullCancellable(
    /** 单条解析或领域映射回调，不应在其中发起网络请求。 */
    transform: suspend (Input) -> Output?,
): List<Output> {
    /** 按原始顺序保留非空映射结果。 */
    val outputs = mutableListOf<Output>()
    /** element 是当前输入，每轮回调前都检查协程取消。 */
    for (element in this) {
        currentCoroutineContext().ensureActive()
        /** 当前映射结果为空时只跳过该元素。 */
        val output = transform(element) ?: continue
        outputs += output
    }
    return outputs
}
