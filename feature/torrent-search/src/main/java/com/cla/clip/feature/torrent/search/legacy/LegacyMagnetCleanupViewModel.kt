package com.cla.clip.feature.torrent.search.legacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 首次进入门禁状态；旧数据未确认清理前不会创建 TorrentSearch 页面。 */
internal sealed interface LegacyMagnetCleanupState {
    /** 正在应用私有固定位置检测旧数据。 */
    data object Checking : LegacyMagnetCleanupState
    /** 未发现旧数据或已精确清理完成，可以进入新功能。 */
    data object Ready : LegacyMagnetCleanupState
    /** 检测到旧数据，等待用户确认永久清理。 */
    data class ConfirmationRequired(
        /** 只含数量和内部白名单目标的检测快照。 */
        val snapshot: LegacyMagnetDataSnapshot,
    ) : LegacyMagnetCleanupState
    /** 用户确认后正在 IO dispatcher 删除固定目标。 */
    data object Cleaning : LegacyMagnetCleanupState
    /** 检测或清理失败，必须重试或退出，不能绕过门禁。 */
    data object Failed : LegacyMagnetCleanupState
}

/** 旧数据门禁 ViewModel，只编排检测、确认和精确清理生命周期。 */
@HiltViewModel
internal class LegacyMagnetCleanupViewModel @Inject constructor(
    /** 文件系统副作用被限制在具名清理器中。 */
    private val cleaner: LegacyMagnetDataCleaner,
) : ViewModel() {
    /** 可变状态只在 ViewModel 内更新。 */
    private val mutableState = MutableStateFlow<LegacyMagnetCleanupState>(LegacyMagnetCleanupState.Checking)
    /** Activity 生命周期感知收集的只读门禁状态。 */
    val state: StateFlow<LegacyMagnetCleanupState> = mutableState.asStateFlow()

    /** ViewModel 创建后开始一次固定位置检测；不访问网络或用户选择存储。 */
    init {
        detect()
    }

    /** 用户确认后清理检测快照；非确认状态调用不会产生副作用。 */
    fun confirmCleanup() {
        /** required 是当前等待确认的稳定快照。 */
        val required = mutableState.value as? LegacyMagnetCleanupState.ConfirmationRequired ?: return
        mutableState.value = LegacyMagnetCleanupState.Cleaning
        viewModelScope.launch {
            try {
                /** result 在 IO dispatcher 执行精确文件删除。 */
                val result = withContext(Dispatchers.IO) { cleaner.clean(required.snapshot) }
                if (!result.isSuccess) {
                    mutableState.value = LegacyMagnetCleanupState.Failed
                    return@launch
                }
                /** remaining 重新检测，避免确认后出现残留仍进入新功能。 */
                val remaining = withContext(Dispatchers.IO) { cleaner.detect() }
                mutableState.value = if (remaining.hasLegacyData) LegacyMagnetCleanupState.Failed else LegacyMagnetCleanupState.Ready
            /** cancellation 保持 ViewModel 清理的结构化取消语义。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** exception 不向 UI 暴露路径或底层异常消息。 */
            } catch (exception: Exception) {
                mutableState.value = LegacyMagnetCleanupState.Failed
            }
        }
    }

    /** 失败页重试固定位置检测，仍不允许跳过确认。 */
    fun retry() {
        if (mutableState.value != LegacyMagnetCleanupState.Failed) return
        detect()
    }

    /** 在 IO dispatcher 执行检测并映射成门禁状态。 */
    private fun detect() {
        mutableState.value = LegacyMagnetCleanupState.Checking
        viewModelScope.launch {
            try {
                /** snapshot 只包含精确命中的固定旧目标。 */
                val snapshot = withContext(Dispatchers.IO) { cleaner.detect() }
                mutableState.value = if (snapshot.hasLegacyData) {
                    LegacyMagnetCleanupState.ConfirmationRequired(snapshot)
                } else {
                    LegacyMagnetCleanupState.Ready
                }
            /** cancellation 保持页面退出时的结构化取消语义。 */
            } catch (cancellation: CancellationException) {
                throw cancellation
            /** exception 统一进入可重试失败态，不输出路径。 */
            } catch (exception: Exception) {
                mutableState.value = LegacyMagnetCleanupState.Failed
            }
        }
    }
}
