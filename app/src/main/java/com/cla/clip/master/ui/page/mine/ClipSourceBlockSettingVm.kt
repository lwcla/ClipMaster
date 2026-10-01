package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.logW
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.R
import com.cla.clip.master.installedapps.InstalledAppReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 页面入口只连接设置订阅、单项提交与可见扫描，持久化和平台读取沿用原协作者。 */
@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClipSourceBlockSettingVm @Inject constructor(
    /** 应用 Context 仅用于错误提示和提交后备份调度，不持有页面生命周期。 */
    @param:ApplicationContext private val appContext: Context,
    /** 已有 IO 读取器，扫描范围、名称和 Drawable 契约保持现状。 */
    installedAppReader: InstalledAppReader,
    /** 独立字段 Flow 与原子增删入口，仍是名单唯一事实来源。 */
    private val clipSettingsRepository: ClipSettingsRepository,
) : ViewModel() {
    companion object {
        /** 页面设置与扫描编排的日志归属，禁止输出包名或搜索词。 */
        private const val TAG = "ClipSourceBlockSettingVm"
    }

    /** 重读序号只重新订阅设置，不能把失败快照改写为空名单。 */
    private val settingsReload = MutableStateFlow(0L)
    /** 设置读取错误与扫描错误分开，读取失败时禁止修改规则。 */
    private val mutableSettingsLoadFailed = MutableStateFlow(false)
    /** 页面观察的设置读取失败状态。 */
    val settingsLoadFailed = mutableSettingsLoadFailed.asStateFlow()
    /** 已提交过滤名单；首次没有有效快照时为 null，供页面禁用操作。 */
    val blockedClipSourcePackages = settingsReload.flatMapLatest {
        // 每次重读只订阅名单字段，其他剪贴设置变化不驱动本页。
        clipSettingsRepository.blockedPackages
            .onEach { mutableSettingsLoadFailed.value = false }
            .catch { error ->
                // error 的 message 可能携带本地路径，仅记录类型与低敏原因。
                if (error is CancellationException) throw error
                logE(TAG) { "读取来源过滤名单失败 reasonCode=settings_read_failed errorType=${error.javaClass.simpleName}" }
                mutableSettingsLoadFailed.value = true
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 单项写入独立于扫描取消，仅提交规则数据，失败保持已提交规则。 */
    private val writeCoordinator = ClipSourceBlockWriteCoordinator(
        scope = viewModelScope,
        readPackages = { clipSettingsRepository.snapshot().blockedPackages },
        addPackage = clipSettingsRepository::addBlockedPackage,
        removePackage = clipSettingsRepository::removeBlockedPackage,
        onFailure = ::showWriteFailure,

        onDiagnostic = ::logWriteStage,
    )
    /** 页面内部提交/返回状态，不公开为跨模块协议。 */
    internal val writeState = writeCoordinator.state

    /** 可见扫描与写入共享 ViewModel 生命周期，但扫描有独立取消边界。 */
    private val scanCoordinator = ClipSourceBlockInstalledAppCoordinator(
        scope = viewModelScope,
        reader = installedAppReader,
        onDiagnostic = ::logScanStage,
    )
    /** 当前会话扫描状态、搜索和系统范围，均不持久化。 */
    val installedAppListState = scanCoordinator.state
    /** 候选及来源摘要一起发布，沿用扫描映射、后台调度及已过滤优先，避免 UI 混用两次扫描。 */
    internal val blockedSourceAppCandidateSnapshot = combine(blockedClipSourcePackages, scanCoordinator.snapshot) { packages, snapshot ->
        // packages 是完整已提交名单；snapshot 只含成功扫描范围，摘要引用必须随映射一起保留。
        BlockedSourceAppCandidateSnapshot(
            candidates = buildBlockedSourceAppCandidates(packages.orEmpty(), snapshot?.apps.orEmpty()),
            loadSummary = snapshot?.summary,
        )
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default),
        SharingStarted.WhileSubscribed(5_000),
        BlockedSourceAppCandidateSnapshot(),
    )

    /** 页面恢复前台时按需扫描，同会话同范围成功后不重复 IO。 */
    fun onVisible() = scanCoordinator.onVisible()

    /** 页面暂停或退出时仅停止扫描，不取消已经接受的名单保存。 */
    fun onHidden() = scanCoordinator.onHidden()

    /** 下拉刷新保留旧列表，重复手势不会替换当前扫描。 */
    fun refreshInstalledApps() = scanCoordinator.refresh()

    /** 系统范围变化会替换扫描，范围语义由原 reader 和展示规则保证。 */
    fun updateShowSystemApps(showSystemApps: Boolean) = scanCoordinator.updateShowSystemApps(showSystemApps)

    /** 搜索仅更新会话条件，不写设置或启动系统扫描。 */
    fun updateSearchQuery(query: String) = scanCoordinator.updateSearchQuery(query)

    /** 设置失败时显式重读，新快照到达前保留禁用状态。 */
    fun retrySettingsLoad() {
        settingsReload.update { sequence -> sequence + 1L }
    }

    /** 会话操作号只串联低频写入阶段，成功区分真实变化与幂等，不输出应用身份。 */
    private fun logWriteStage(stage: String, operationId: Long, blocked: Boolean) {
        /** 日志中的动作语义由目标布尔值派生，与用户包名或名称无关。 */
        val operation = if (blocked) "add" else "remove"
        when (stage) {
            "start" -> logD(TAG) { "开始修改来源过滤 operationId=$operationId operation=$operation" }
            "changed" -> logI(TAG) { "来源过滤修改完成 operationId=$operationId operation=$operation changed=true" }
            "unchanged" -> logI(TAG) { "来源过滤目标已满足 operationId=$operationId operation=$operation changed=false" }
            "rejected" -> logW(TAG) { "来源过滤修改被拒绝 operationId=$operationId operation=$operation changed=false" }
            "failure" -> logE(TAG) { "来源过滤提交失败 operationId=$operationId operation=$operation" }
            "cancelled" -> logD(TAG) { "来源过滤任务结束取消 operationId=$operationId operation=$operation" }
        }
    }

    /** 逐项提交明确目标，读取失败时拒绝修改，不因为提交成功自动离页。 */
    fun setSourceBlocked(packageName: String, blocked: Boolean) {
        if (blockedClipSourcePackages.value == null || mutableSettingsLoadFailed.value) return
        writeCoordinator.setBlocked(packageName, blocked)
    }

    /** true 可立即导航；false 等已接受写入结束，由 writeState 自动导航。 */
    fun requestBack(): Boolean = writeCoordinator.requestBack()

    /** 失败使用资源化提示，异常正文和包名不进入用户提示或日志。 */
    private fun showWriteFailure(reason: ClipSourceBlockWriteFailure, error: Throwable?) {
        // toast 是既有挂起平台接口，提示协程与页面扫描取消独立，不阻塞提交状态收尾。
        viewModelScope.launch {
            when (reason) {
                ClipSourceBlockWriteFailure.Capacity -> {
                    logW(TAG) { "来源过滤新增被拒绝 reasonCode=too_many_packages" }
                    appContext.toast(appContext.getString(R.string.host_clip_source_filter_limit_reached, ClipSourceBlockRules.MAX_PACKAGE_COUNT))
                }
                ClipSourceBlockWriteFailure.InvalidPackage -> {
                    logW(TAG) { "来源过滤修改被拒绝 reasonCode=invalid_package" }
                    appContext.toast(R.string.host_clip_source_filter_invalid_package)
                }
                ClipSourceBlockWriteFailure.Storage -> {
                    logE(TAG) { "来源过滤名单保存失败 reasonCode=settings_write_failed errorType=${error?.javaClass?.simpleName}" }
                    appContext.toast(R.string.host_settings_save_failed_retry)
                }
            }
        }
    }

    /** 扫描记录阶段、数量、范围与原因，正常取消不记异常，不输出应用身份。 */
    private fun logScanStage(stage: String, state: InstalledAppListState) {
        when (stage) {
            "start" -> logD(TAG) { "开始读取安装应用 showSystemApps=${state.showSystemApps}" }
            "success" -> logI(TAG) { "安装应用读取完成 appCount=${state.loadedAppCount} elapsedMs=${state.lastLoadSummary?.elapsedMs} showSystemApps=${state.showSystemApps}" }
            "failure" -> logW(TAG) { "安装应用读取失败 reasonCode=${state.unavailableReasonCode} retainedCount=${state.loadedAppCount} showSystemApps=${state.showSystemApps}" }
            "cancelled" -> logD(TAG) { "页面不可见取消安装扫描 retainedCount=${state.loadedAppCount}" }
        }
    }
}
