package com.cla.clip.master.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.privacy.PrivacySettingsRepository
import com.cla.clip.base.general.config.privacy.PrivacySettingsSnapshot
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logW
import com.cla.clip.master.utils.ShizukuConnector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import rikka.shizuku.Shizuku

/** 首次启动只区分介质加载、失败与已知选择，失败不得被默认值掩盖。 */
internal sealed interface FirstLaunchUiState {
    /** 启动时等待一次轻量设置读取。 */ data object Loading : FirstLaunchUiState
    /** 读取失败时展示重试，不读取剪贴板或发起预览。 */ data object Failed : FirstLaunchUiState
    /** 事务确认后的显示数据。 */
    data class Ready(
        /** 当前已提交设置。 */ val settings: PrivacySettingsSnapshot,
        /** 正在提交选择，两个按钮暂不可重复点击。 */ val saving: Boolean = false,
        /** 写入失败保持原选择并展示重试提示。 */ val saveFailed: Boolean = false,
    ) : FirstLaunchUiState
}

/** 仅负责首次说明和一次权限序列；联网开关独立由设置页管理。 */
@HiltViewModel
internal class FirstLaunchVm @Inject constructor(
    /** 本机安装识别与一次初始化。 */ private val initializer: FirstLaunchInitializer,
    /** 已提交的隐私选择。 */ private val repository: PrivacySettingsRepository,
    /** 应用级连接器不持有 Activity，授权回调在界面重建期间仍可使用。 */ connector: ShizukuConnector,
) : ViewModel() {
    /** Activity 旋转保留同一动作序列，进程重建后不自动重放权限申请。 */
    val permissions = FirstLaunchPermissionSequence()
    /** 与权限序列共用 ViewModel 生命周期，消除旋转空档中的 SDK 结果丢失。 */
    private val shizukuSubscription = FirstLaunchShizukuSubscription(
        permissions, Shizuku::addRequestPermissionResultListener, Shizuku::removeRequestPermissionResultListener,
        onGranted = { connector.connect() },
    )
    /** 首次启动宿主所需的唯一 UI 状态。 */
    private val mutableState = MutableStateFlow<FirstLaunchUiState>(FirstLaunchUiState.Loading)
    /** UI 按生命周期订阅，不自行编辑持久状态。 */
    val state = mutableState.asStateFlow()
    /** 显式重试时替换旧设置观察。 */
    private var loading: Job? = null
    /** 外部 Shizuku/下载页面返回只推进一次，不因旋转重复跳转。 */
    var waitingForExternalReturn = false
    /** 确认经历暂停后再处理恢复，避免注册观察器时立即推进。 */
    var externalPagePaused = false

    init { reload() }

    /** Activity 真正结束才释放 SDK 订阅；配置重建保留 ViewModel 与监听。 */
    override fun onCleared() {
        shizukuSubscription.close()
        super.onCleared()
    }

    /** 首次启动需要读取小型设置，与具体导航页面无关；重试时取消旧观察。 */
    fun reload() {
        loading?.cancel()
        mutableState.value = FirstLaunchUiState.Loading
        loading = viewModelScope.launch {
            try {
                initializer.initialize()
                repository.state.collect { snapshot ->
                    /** 保留进行中的提交状态，快照只由 DataStore 回流更新。 */
                    val previous = mutableState.value as? FirstLaunchUiState.Ready
                    mutableState.value = FirstLaunchUiState.Ready(snapshot, previous?.saving == true, previous?.saveFailed == true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.value = FirstLaunchUiState.Failed
                logW("FirstLaunch") { "首次设置读取失败 reasonCode=settings_read_failed type=${failure.javaClass.simpleName}" }
            }
        }
    }

    /** 先持久化再安排权限动作；暂不开启也完成说明，之后前台读取照常。 */
    fun choose(accepted: Boolean) {
        /** 只允许在真实加载完成且尚未处理时提交。 */
        val current = mutableState.value as? FirstLaunchUiState.Ready ?: return
        if (current.saving || current.settings.firstLaunchCompleted) return
        mutableState.value = current.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                /** 事务返回 false 表示已有选择，不能重放自动授权。 */
                val completedNow = repository.completeFirstLaunch(enablePreview = accepted)
                mutableState.value = FirstLaunchUiState.Ready(repository.snapshot())
                if (completedNow) permissions.begin(accepted)
                logD("FirstLaunch") { "首次说明处理完成 accepted=$accepted changed=$completedNow" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.value = current.copy(saveFailed = true)
                logW("FirstLaunch") { "首次选择保存失败 reasonCode=settings_write_failed type=${failure.javaClass.simpleName}" }
            }
        }
    }
}
