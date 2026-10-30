package com.cla.clip.master.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 仅在已提交许可为 true 时执行一次工作；关闭/读设置失败返回 null，外部取消及工作异常继续传播。
 * 观察与工作属于同一作用域，退出后没有遗留订阅；collectLatest 将关闭事件传递给在途网络任务。
 */
internal suspend fun <T> withPreviewPermission(permission: Flow<Boolean>, work: suspend () -> T): T? = coroutineScope {
    /** 网络完成或被关闭时通知调用方；关闭不会撤销调用前已保存的剪贴文本。 */
    val result = CompletableDeferred<T?>()
    /** 只在一次自动预览期间观察设置，禁止后台常驻轮询。 */
    val observer = launch {
        permission.distinctUntilChanged().catch { failure ->
            if (failure is CancellationException) throw failure
            emit(false)
        }.collectLatest { allowed ->
            if (!allowed) result.complete(null)
            else if (!result.isCompleted) result.complete(work())
        }
        // 无数据的已结束流同样不构成联网同意。
        result.complete(null)
    }
    try { result.await() } finally { observer.cancelAndJoin() }
}
