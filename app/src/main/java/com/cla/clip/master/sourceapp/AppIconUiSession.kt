package com.cla.clip.master.sourceapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单个 UI 绑定的图像持有边界；取消可重试，关闭后永久拒绝晚到结果。 */
internal class AppIconUiSession<Icon>(
    /** 包名仅供按需加载，未知身份不访问系统。 */
    private val packageName: String?,
    /** 可替换加载入口；生产实现复用 Manager 去重与缓存。 */
    private val read: suspend (String) -> AppIconEntry<Icon>,
) {
    /** 串行处理同一个绑定的重复可见请求，避免重组造成重复加载。 */
    private val loadLock = Mutex()
    /** 关闭与结果发布共用短锁，IO 不占用此锁。 */
    private val stateLock = Any()
    /** 永久关闭标志，仅在组合销毁时设置，普通暂停不关闭。 */
    private var closed = false
    /** null 表示尚未完成；全空条目表示已完成但使用默认展示。 */
    private val result = MutableStateFlow<AppIconEntry<Icon>?>(null)
    /** UI 只读状态，无法绕过关闭与取消边界修改结果。 */
    val state: StateFlow<AppIconEntry<Icon>?> = result.asStateFlow()

    /** 仅在可见生命周期调用；取消不交付默认结果，恢复后可继续尝试。 */
    suspend fun load() = loadLock.withLock {
        if (synchronized(stateLock) { closed || result.value != null }) return@withLock
        /** 空白身份立即默认；普通失败同样默认，取消单独传播。 */
        val loaded: AppIconEntry<Icon> = try {
            if (packageName.isNullOrBlank()) AppIconEntry(null, null) else read(packageName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppIconEntry(null, null)
        }
        // 系统同步调用可能不可中断，返回后再次检查取消，禁止隐藏页面发布旧结果。
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) {
            if (!closed) result.value = loaded
        }
    }

    /** 条目离开组合立即释放图片；正在返回的系统读取也无法重新占用该状态。 */
    fun close() = synchronized(stateLock) {
        closed = true
        result.value = null
    }
}
