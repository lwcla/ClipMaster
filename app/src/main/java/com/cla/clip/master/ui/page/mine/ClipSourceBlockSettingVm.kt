package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.ManualPackageValidationResult
import com.cla.clip.base.general.config.clip.ClipSettingsRepository
import com.cla.clip.base.general.utils.logE
import com.cla.clip.base.general.utils.logI
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.installedapps.INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY
import com.cla.clip.master.installedapps.InstalledAppInfo
import com.cla.clip.master.installedapps.InstalledAppLoadResult
import com.cla.clip.master.installedapps.InstalledAppReader
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/**
 * 剪贴来源过滤设置页 ViewModel。
 *
 * 负责页面触发的安装应用与图标读取、搜索和系统开关；设置本身仍只保存完整包名。
 */
@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClipSourceBlockSettingVm @Inject constructor(
    /** 应用级 Context，用于读取当前应用包名和调度过滤名单备份。 */
    @param:ApplicationContext private val appContext: Context,
    /** 主进程安装应用读取器，依赖 QUERY_ALL_PACKAGES 读取当前用户可见应用。 */
    private val installedAppReader: InstalledAppReader,

    /** 原子保存来源过滤名单；页面草稿不直接接触持久化键。 */
    private val clipSettingsRepository: ClipSettingsRepository,
) : ViewModel() {

    companion object {
        /** 设置页日志标签，方便区分主页 MineVm 日志。 */
        private const val TAG = "ClipSourceBlockSettingVm"
    }

    /** 设置重读序号；读取失败后由页面点击重试触发重新订阅。 */
    private val settingsReload = MutableStateFlow(0L)

    /** 设置读取失败标记；安装应用扫描失败与此独立。 */
    private val _settingsLoadFailed = MutableStateFlow(false)

    /** 页面可观察的设置读取失败状态。 */
    val settingsLoadFailed = _settingsLoadFailed.asStateFlow()

    /** 已提交的来源过滤名单；null 表示首次读取未完成或重试尚未成功。 */
    val blockedClipSourcePackages = settingsReload.flatMapLatest {
        clipSettingsRepository.blockedPackages
            .onEach { _settingsLoadFailed.value = false }
            .catch { error ->
                if (error is CancellationException) throw error
                logE(TAG, error) { "读取来源过滤名单失败 reasonCode=settings_read_failed" }
                _settingsLoadFailed.value = true
            }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    /** 用户显式重试读取设置；不会清空已保存过滤规则。 */
    fun retrySettingsLoad() {
        // 旧快照保持禁用，直到本次重读实际发出已提交名单。
        settingsReload.update { sequence -> sequence + 1L }
    }

    /** 当前页面会话读取到的安装应用列表；不写入数据库、DataStore 或备份。 */
    private val installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())

    /** 安装应用读取内存态；加载/失败/摘要不写入数据库或备份。 */
    private val _installedAppListState = MutableStateFlow(InstalledAppListState())

    /** 设置页订阅的读取状态、搜索和系统应用开关。 */
    val installedAppListState = _installedAppListState.asStateFlow()

    /** 当前安装应用读取任务；连续刷新时取消旧任务，避免旧扫描继续消耗资源。 */
    private var installedAppLoadJob: Job? = null

    /**
     * 来源过滤候选列表。
     *
     * 仅从当前扫描结果构建候选，已保存屏蔽项优先；不观察历史来源或为未安装规则生成占位行。
     */
    val blockedSourceAppCandidates = combine(
        blockedClipSourcePackages,
        installedApps,
    ) { blockedPackages, currentInstalledApps ->
        buildBlockedSourceAppCandidates(
            blockedPackages = blockedPackages.orEmpty(),
            installedApps = currentInstalledApps,
        )
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default),
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    /** 页面自动入口仅加载当前会话尚未成功读取的安装列表；首次失败允许下次进入时重试。 */
    fun loadInstalledAppsIfNeeded() {
        /** 当前页面会话的读取内存态；随导航条目的 ViewModel 清理，不提供跨页面缓存。 */
        val state = _installedAppListState.value
        // 正在读取或已有成功结果时仅跳过自动入口，手动刷新不经过此判断。
        if (!state.needsInitialLoad) {
            /** 自动跳过的低敏原因；读取中优先于已成功，避免误把任务替换当成过期刷新。 */
            val reasonCode = if (state.loading) "already_loading" else "already_loaded"
            logI(TAG) {
                "跳过自动读取安装应用 reasonCode=$reasonCode"
            }
            return
        }
        refreshInstalledApps()
    }

    /**
     * 重新读取本机安装应用；自动入口先判断会话状态，用户手动刷新始终直接重读。
     *
     * 页面在主线程调用；新刷新取消旧 Job，旧扫描晚返回时通过取消检查阻止状态写入。
     * 取消异常由协程直接传播，不转换成读取失败，也不单独维护请求序号。
     */
    fun refreshInstalledApps() {
        /** 本次读取范围在主线程固定；开关变化取消旧任务，不让旧范围晚返回后覆盖新结果。 */
        val showSystemApps = _installedAppListState.value.showSystemApps
        // 立即取消旧任务；不等待不可中断的系统扫描结束，避免延迟本次刷新。
        installedAppLoadJob?.cancel()
        // 读取任务归 ViewModel 管理；取消后不再应用结果，也不清除新任务的 loading 状态。
        installedAppLoadJob = viewModelScope.launch {
            logI(TAG) { "开始读取安装应用 showSystemApps=$showSystemApps" }
            // state 是已有读取状态；开始刷新时保留旧列表并清除上次失败与摘要。
            _installedAppListState.update { state ->
                state.copy(
                    loading = true,
                    unavailableReasonCode = null,
                    lastLoadSummary = null,
                )
            }
            /** 本次 PackageManager 读取结果；成功时刷新内存列表，失败时按原因保留或清空旧列表。 */
            val loadResult = installedAppReader.loadInstalledApps(showSystemApps)
            // 同步扫描可能晚返回；在主线程无挂起的状态写入前检查当前任务是否已取消。
            currentCoroutineContext().ensureActive()
            when (loadResult) {
                is InstalledAppLoadResult.Success -> applyInstalledAppLoadSuccess(loadResult)
                is InstalledAppLoadResult.Failed -> applyInstalledAppLoadFailure(loadResult)
            }
        }
    }

    /** 应用安装应用读取成功结果，并刷新页面候选来源。 */
    private fun applyInstalledAppLoadSuccess(loadResult: InstalledAppLoadResult.Success) {
        /** 读取到的安装应用列表；只保存在当前页面会话内存中。 */
        val apps = loadResult.apps
        installedApps.value = apps
        _installedAppListState.update { state ->
            state.copy(
                loading = false,
                everLoaded = true,
                unavailableReasonCode = null,
                loadedAppCount = apps.size,
                lastLoadSummary = InstalledAppLoadSummary(
                    appCount = apps.size,
                    elapsedMs = loadResult.elapsedMs,
                    loadedAtMillis = loadResult.loadedAtMillis,
                )
            )
        }
        logI(TAG) {
            "installed_app_load_result reasonCode=success appCount=${apps.size} elapsedMs=${loadResult.elapsedMs}"
        }
    }

    /** 应用安装应用读取失败结果；普通失败保留当前内存列表，空列表失败清空内存列表。 */
    private fun applyInstalledAppLoadFailure(loadResult: InstalledAppLoadResult.Failed) {
        if (loadResult.reasonCode == INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY) {
            installedApps.value = emptyList()
        }
        _installedAppListState.update { state ->
            state.copy(
                loading = false,
                unavailableReasonCode = loadResult.reasonCode,
                loadedAppCount = installedApps.value.size,
                lastLoadSummary = null,
            )
        }
        logI(TAG) {
            "installed_app_load_result reasonCode=${loadResult.reasonCode} appCount=${installedApps.value.size} " +
                    "elapsedMs=${loadResult.elapsedMs}"
        }
    }

    /**
     * 切换系统应用显示开关。
     *
     * 该开关只影响扫描范围和页面内存态，不进入持久化或备份；双向变化都重新读取名称和图标。
     */
    fun updateShowSystemApps(showSystemApps: Boolean) {
        // 重复同值不扫描；范围改变立即替换旧任务，失败仍保留旧快照及完整草稿。
        if (_installedAppListState.value.showSystemApps == showSystemApps) return
        _installedAppListState.update { state -> state.copy(showSystemApps = showSystemApps) }
        refreshInstalledApps()
    }

    /** 设置候选搜索关键词；搜索只在页面内存中过滤候选，不影响已保存名单。 */
    fun updateSearchQuery(query: String) {
        _installedAppListState.update { state -> state.copy(searchQuery = query) }
    }

    /**
     * 替换剪贴来源 App 过滤名单。
     *
     * 设置页确认多选时调用；仓库原子规范化并提交，成功后才触发返回页面。
     */
    fun replaceBlockedSourcePackages(packages: Set<String>, onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                if (clipSettingsRepository.replaceBlockedPackages(packages)) {
                    try {
                        BackupAutoScheduler.scheduleAfterCommittedChange(appContext)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // 已提交名单和 dirty 版本保留，周期备份可继续兜底。
                        logE(TAG, error) { "来源过滤已保存但备份调度失败 reasonCode=backup_schedule_failed" }
                    }
                }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logE(TAG, error) { "来源过滤名单保存失败 reasonCode=settings_write_failed" }
                appContext.toast(com.cla.clip.master.R.string.host_settings_save_failed_retry)
            }
        }
    }

    /**
     * 手动添加包名到来源过滤名单草稿。
     *
     * 返回校验结果，UI 根据结果加入当前页面草稿；真正落盘仍由确认按钮统一替换名单。
     */
    fun addManualBlockedPackage(packageName: String, currentPackageCount: Int): ManualPackageValidationResult {
        /** 手动添加校验结果；包含 trim 后包名或失败原因。 */
        val validationResult = ClipSourceBlockRules.validateManualPackage(
            packageName = packageName,
            selfPackageName = appContext.packageName,
            currentPackageCount = currentPackageCount
        )
        if (validationResult is ManualPackageValidationResult.Valid) {
            logI(TAG) { "手动添加来源过滤包名进入草稿 reasonCode=manual_package_added" }
        } else if (validationResult is ManualPackageValidationResult.SelfPackage) {
            logI(TAG) { "拒绝手动添加当前应用 reasonCode=self_package_rejected" }
        }
        return validationResult
    }
}

/**
 * 来源过滤设置页安装列表内存态。
 *
 * 安装列表只在页面会话内存保存；这里记录读取状态、搜索关键词和系统应用开关。
 */
data class InstalledAppListState(
    /** 是否正在读取本机安装应用列表。 */
    val loading: Boolean = false,
    /** 当前页面会话是否至少成功读取过一次安装列表。 */
    val everLoaded: Boolean = false,
    /** 本机安装应用读取失败的低敏原因码；为空表示当前没有错误。 */
    val unavailableReasonCode: String? = null,
    /** 是否显示系统应用；关闭时已保存屏蔽、草稿勾选和搜索均不能放行系统应用。 */
    val showSystemApps: Boolean = false,
    /** 设置页搜索关键词，只影响候选展示。 */
    val searchQuery: String = "",
    /** 最近一次成功读取摘要；只保存在内存中，不进入数据库或备份。 */
    val lastLoadSummary: InstalledAppLoadSummary? = null,
    /** 当前内存安装应用数量；失败保留旧列表时用于头部摘要。 */
    val loadedAppCount: Int = 0,
) {
    /** 当前会话是否需要自动读取；首次失败允许重试，成功后仅通过手动刷新更新列表。 */
    internal val needsInitialLoad: Boolean
        get() = !loading && !everLoaded
}

/** 安装应用读取摘要。 */
data class InstalledAppLoadSummary(
    /** 本次读取到的 App 数量。 */
    val appCount: Int,
    /** 本次读取耗时毫秒。 */
    val elapsedMs: Long,
    /** 本次成功读取完成时间；只用于页面会话的读取摘要，不参与自动加载判断。 */
    val loadedAtMillis: Long,
)
