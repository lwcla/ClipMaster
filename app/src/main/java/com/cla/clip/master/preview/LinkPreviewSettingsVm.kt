package com.cla.clip.master.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.privacy.PrivacySettingsRepository
import com.cla.clip.base.general.utils.logW
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 预览开关只表达已提交值，首次加载和错误不能伪装成关闭。 */
internal sealed interface LinkPreviewToggleState {
    /** 设置首次加载期间禁用编辑。 */ data object Loading : LinkPreviewToggleState
    /** 读取失败允许重试。 */ data object Failed : LinkPreviewToggleState
    /** 唯一已提交的开关值。 */ data class Ready(/** 是否允许自动联网。 */ val enabled: Boolean) : LinkPreviewToggleState
}

/** 我的页独立预览设置接线，页面不可见时停止收集；不连接 Shizuku 或通知权限。 */
@HiltViewModel
internal class LinkPreviewSettingsVm @Inject constructor(
    /** 只操作本机预览字段。 */ private val repository: PrivacySettingsRepository,
) : ViewModel() {
    /** 显式重试重建失败的冷流。 */ private val retry = MutableStateFlow(0)
    /** 提交期间阻止重复点击，失败后维持旧值。 */ private val mutableSaving = MutableStateFlow(false)
    /** 提交失败持久到下一次操作，页面返回仍能看到反馈。 */ private val mutableSaveFailed = MutableStateFlow(false)
    /** 已提交值之外的纯 UI 忙碌状态。 */ val saving = mutableSaving.asStateFlow()
    /** 资源化失败提示由页面展示。 */ val saveFailed = mutableSaveFailed.asStateFlow()
    /** 只在订阅期间读取设置，不在初始化时执行网络或扫描。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state = retry.flatMapLatest {
        repository.previewEnabled.map<Boolean, LinkPreviewToggleState> { LinkPreviewToggleState.Ready(it) }
            .onStart { emit(LinkPreviewToggleState.Loading) }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                logW("LinkPreviewSettings") { "预览设置读取失败 type=${failure.javaClass.simpleName}" }
                emit(LinkPreviewToggleState.Failed)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), LinkPreviewToggleState.Loading)

    /** 仅重试设置观察，不修改任何用户选择。 */
    fun reload() { retry.update { it + 1 } }

    /** 保存成功后由仓库 Flow 更新开关；失败不产生权限副作用。 */
    fun setEnabled(enabled: Boolean) {
        if (state.value !is LinkPreviewToggleState.Ready || mutableSaving.value) return
        mutableSaving.value = true
        mutableSaveFailed.value = false
        viewModelScope.launch {
            try { repository.setPreviewEnabled(enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableSaveFailed.value = true
                logW("LinkPreviewSettings") { "预览设置保存失败 type=${failure.javaClass.simpleName}" }
            } finally { mutableSaving.value = false }
        }
    }
}
