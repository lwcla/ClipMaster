package com.cla.clip.master.logging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.logging.LogSettingsRepository
import com.cla.clip.base.general.utils.logW
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** 设置加载与失败不可被 Boolean 默认关闭掩盖。 */
internal sealed interface LogToggleState {
    /** 尚未读取已提交开关。 */
    data object Loading : LogToggleState
    /** 唯一已提交设置值；不代表原生文件写入一定成功。 */
    data class Ready(/** 用户持久保存的读取/保存意图。 */ val enabled: Boolean) : LogToggleState
    /** 设置读取失败，禁止编辑未知旧值。 */
    data object Failed : LogToggleState
}

/** 实际目录数量、加载与失败的独立状态。 */
internal sealed interface LogFileCountState {
    /** 首次查询或重试中。 */
    data object Loading : LogFileCountState
    /** 可识别且当前可访问的日志文件数。 */
    data class Ready(/** 只读一级元数据得到的实际数量。 */ val count: Int) : LogFileCountState
    /** 权限/枚举失败，不能显示为 0。 */
    data object Failed : LogFileCountState
}

/** 共享卡片只展示稳定 UI 模型，不连接采集器、目录或设置副作用。 */
internal data class LogSettingsEntryState(
    /** 开关加载/已提交/失败。 */ val toggle: LogToggleState = LogToggleState.Loading,
    /** 目录数量独立于开关。 */ val files: LogFileCountState = LogFileCountState.Loading,
    /** 应用级采集运行状态，离页不会停止。 */ val capture: LogCaptureState = LogCaptureState(),
    /** 单次设置提交忙碌，避免重复写入。 */ val updating: Boolean = false,
)

/** 日志 item 的轻量接线；统计只有订阅时工作，采集由 Application 控制。 */
@HiltViewModel
internal class LogSettingsVm @Inject constructor(
    /** 设置只保存本机开关。 */ private val settings: LogSettingsRepository,
    /** 应用级录制状态，不随 ViewModel 销毁取消采集。 */ private val capture: LogcatCaptureController,
    /** 页面可见期间的轻量目录观察。 */ private val files: LogDirectoryStore,
) : ViewModel() {
    /** 返回系统文件界面/设置提交后显式刷新目录。 */
    private val refresh = MutableStateFlow(0L)
    /** 编辑期间只改变 UI 忙碌，不先伪造已提交开关。 */
    private val updating = MutableStateFlow(false)
    /** 一次性保存失败供前台资源化提示。 */
    private val mutableErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** 页面可见期间消费，未显示时不触发后台 Toast。 */
    val errors = mutableErrors.asSharedFlow()

    /** 设置/目录冷流跟随订阅，加载和失败都有独立表达；不在 init 扫描。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state = refresh.flatMapLatest {
        combine(
            settings.enabled.map<Boolean, LogToggleState> { LogToggleState.Ready(it) }
                .catch { failure -> if (failure is CancellationException) throw failure; emit(LogToggleState.Failed) },
            capture.state,
        ) { toggle, recording -> toggle to recording }.flatMapLatest { (toggle, recording) ->
            files.observeCount().map<Int, LogSettingsEntryState> { count ->
                LogSettingsEntryState(toggle, LogFileCountState.Ready(count), recording)
            }.onStart { emit(LogSettingsEntryState(toggle, capture = recording)) }
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    logW("LogSettings") { "日志数量读取失败 reasonCode=log_count_failed type=${failure.javaClass.simpleName}" }
                    emit(LogSettingsEntryState(toggle, LogFileCountState.Failed, recording))
                }
        }
    }.combine(updating) { entry, busy -> entry.copy(updating = busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), LogSettingsEntryState())

    /** 前台返回/重试只刷新元数据，不改变保存目录或采集意图。 */
    fun refreshFiles() {
        // 只有设置观察本身终止时重新接入，不复活文件故障中的已开启采集会话。
        if (capture.state.value.reason == "log_settings_failed") capture.start()
        refresh.update { it + 1 }
    }

    /** 只有成功提交才从 Flow 改变 Switch；读值未知或正在提交时拒绝重复操作。 */
    fun setEnabled(enabled: Boolean) {
        if (state.value.toggle !is LogToggleState.Ready || updating.value) return
        updating.value = true
        viewModelScope.launch {
            try {
                settings.setEnabled(enabled)
                refreshFiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableErrors.tryEmit(Unit)
                logW("LogSettings") { "日志开关保存失败 reasonCode=log_settings_write_failed type=${failure.javaClass.simpleName}" }
            } finally { updating.value = false }
        }
    }
}
