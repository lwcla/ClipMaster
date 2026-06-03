package com.cla.clip.feature.ad.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 广告源会话级失败保险丝。
 *
 * 当前进程内某个广告源初始化或渲染连续失败后，宿主会把它加入禁用集合，后续广告位自动回退到其它 source 或隐藏。
 */
@Singleton
class AdSessionFailureFuse @Inject constructor() {
    /** 当前会话已禁用的广告源 ID 集合；只保存在内存中，不进入备份或持久化设置。 */
    private val disabledSourceIds = MutableStateFlow<Set<String>>(emptySet())

    /** 暴露给宿主收集的禁用广告源集合。 */
    val disabledSourceIdsFlow: StateFlow<Set<String>> = disabledSourceIds.asStateFlow()

    /**
     * 禁用指定广告源。
     *
     * sourceId 为空时忽略，避免异常事件污染整个广告选择器。
     */
    fun disableSource(sourceId: String) {
        /** 清理后的广告源 ID；空值表示事件无法可信归属到具体 source。 */
        val normalizedSourceId = sourceId.trim()
        if (normalizedSourceId.isBlank()) {
            return
        }

        disabledSourceIds.update { currentIds -> currentIds + normalizedSourceId }
    }

    /** 清空当前会话保险丝，主要用于调试或未来设置页手动恢复广告源。 */
    fun reset() {
        disabledSourceIds.value = emptySet()
    }
}
