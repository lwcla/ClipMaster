package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipSourceBlockRules
import com.cla.clip.base.general.config.ManualPackageValidationResult
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.isUsableCachedIconFile
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.installedapps.INSTALLED_APP_REASON_PACKAGE_SCAN_EMPTY
import com.cla.clip.master.installedapps.InstalledAppInfo
import com.cla.clip.master.installedapps.InstalledAppLoadResult
import com.cla.clip.master.installedapps.InstalledAppReader
import com.cla.clip.master.work.BackupAutoScheduler
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 剪贴来源过滤设置页 ViewModel。
 *
 * 负责在页面可见时读取当前安装应用元数据、合并历史来源和维护搜索/系统应用开关；设置本身仍只保存包名。
 */
@HiltViewModel
class ClipSourceBlockSettingVm @Inject constructor(
    /** 应用级 Context，用于读取当前应用包名和调度过滤名单备份。 */
    @param:ApplicationContext private val appContext: Context,
    /** 剪贴数据仓库，提供历史来源和来源图标缓存。 */
    private val clipRepository: Lazy<ClipRepository>,
    /** 主进程安装应用读取器，依赖 QUERY_ALL_PACKAGES 读取当前用户可见应用。 */
    private val installedAppReader: InstalledAppReader,
) : ViewModel() {

    companion object {
        /** 设置页日志标签，方便区分主页 MineVm 日志。 */
        private const val TAG = "ClipSourceBlockSettingVm"

        /** 自动读取节流窗口；10 分钟内已有成功读取时页面进入不重复扫描。 */
        private const val AUTO_LOAD_THROTTLE_MS = 10 * 60 * 1000L
    }

    /** 来源过滤名单，详情页和保存链路也读取同一份 AppSetting 状态。 */
    val blockedClipSourcePackages = AppSetting.blockedClipSourcePackagesFlow

    /** 当前页面会话读取到的安装应用列表；不写入数据库、MMKV 或备份。 */
    private val installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())

    /** 安装应用读取内存态；加载/失败/摘要不写入数据库或备份。 */
    private val _installedAppListState = MutableStateFlow(InstalledAppListState())

    /** 设置页订阅的读取状态、搜索和系统应用开关。 */
    val installedAppListState = _installedAppListState.asStateFlow()

    /** 当前安装应用读取任务；连续刷新时取消旧任务，避免旧扫描继续消耗资源。 */
    private var installedAppLoadJob: Job? = null

    /** 当前安装应用读取请求序号；即使旧任务晚返回也不能覆盖新请求结果。 */
    private var installedAppLoadRequestId: Long = 0L

    /**
     * 来源过滤候选列表。
     *
     * 合并优先级固定为已过滤包名、当前安装应用直读结果、历史来源；同包名名称冲突时当前安装应用名称和图标优先。
     */
    val blockedSourceAppCandidates = combine(
        blockedClipSourcePackages,
        installedApps,
        clipRepository.get().loadAllSourceApps(),
    ) { blockedPackages, currentInstalledApps, historySourceApps ->
        buildBlockedSourceAppCandidates(
            blockedPackages = blockedPackages,
            installedApps = currentInstalledApps,
            historySourceApps = historySourceApps,
        )
    }.stateIn(
        CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default),
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    /** 打开设置页时按节流规则触发本机安装应用读取。 */
    fun loadInstalledAppsIfNeeded() {
        /** 当前读取内存态；如果正在读取或 10 分钟内已成功读取，则跳过自动刷新。 */
        val state = _installedAppListState.value
        /** 当前墙钟时间；只用于页面会话内节流判断，不写入数据库或备份。 */
        val nowMillis = System.currentTimeMillis()
        /** 距离上次成功读取的时间；未成功过时按最大值处理，确保首次进入会触发自动读取。 */
        val elapsedSinceLastSuccess = if (state.everLoaded && state.lastLoadedAtMillis > 0L) {
            nowMillis - state.lastLoadedAtMillis
        } else {
            Long.MAX_VALUE
        }
        if (state.loading) {
            logI(TAG) { "installed_app_load_skip reasonCode=already_loading force=false" }
            return
        }
        if (elapsedSinceLastSuccess < AUTO_LOAD_THROTTLE_MS) {
            logI(TAG) {
                "installed_app_load_skip reasonCode=auto_throttled elapsedMs=$elapsedSinceLastSuccess " +
                    "throttleMs=$AUTO_LOAD_THROTTLE_MS"
            }
            return
        }
        refreshInstalledApps(forceRefresh = false)
    }

    /** 用户手动重新读取本机安装应用列表。 */
    fun refreshInstalledApps() {
        refreshInstalledApps(forceRefresh = true)
    }

    /**
     * 触发本机安装应用读取。
     *
     * @param forceRefresh 是否由用户手动刷新触发；手动刷新绕过 10 分钟自动节流。
     */
    private fun refreshInstalledApps(forceRefresh: Boolean) {
        /** 本次请求序号；旧请求完成时会被丢弃。 */
        val requestId = ++installedAppLoadRequestId
        installedAppLoadJob?.cancel()
        installedAppLoadJob = viewModelScope.launch {
            logI(TAG) { "installed_app_load_start force=$forceRefresh requestId=$requestId" }
            _installedAppListState.update { state ->
                state.copy(
                    loading = true,
                    unavailableReasonCode = null,
                    lastLoadSummary = null,
                )
            }
            try {
                /** 本次 PackageManager 读取结果；成功时刷新内存列表，失败时按原因保留或清空旧列表。 */
                val loadResult = installedAppReader.loadInstalledApps()
                if (requestId != installedAppLoadRequestId) {
                    logI(TAG) { "installed_app_load_drop_stale requestId=$requestId latest=$installedAppLoadRequestId" }
                    return@launch
                }
                when (loadResult) {
                    is InstalledAppLoadResult.Success -> applyInstalledAppLoadSuccess(loadResult)
                    is InstalledAppLoadResult.Failed -> applyInstalledAppLoadFailure(loadResult)
                }
            } catch (error: CancellationException) {
                throw error
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
                lastLoadedAtMillis = loadResult.loadedAtMillis,
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
                lastLoadedAtMillis = loadResult.loadedAtMillis,
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
     * 该开关只影响设置页内存态，不进入持久化或备份。
     */
    fun updateShowSystemApps(showSystemApps: Boolean) {
        _installedAppListState.update { state -> state.copy(showSystemApps = showSystemApps) }
    }

    /** 设置候选搜索关键词；搜索只在页面内存中过滤候选，不影响已保存名单。 */
    fun updateSearchQuery(query: String) {
        _installedAppListState.update { state -> state.copy(searchQuery = query) }
    }

    /**
     * 替换剪贴来源 App 过滤名单。
     *
     * 设置页确认多选时调用；AppSetting 内部会统一去重、排序和裁剪，并标记备份 dirty。
     */
    fun replaceBlockedSourcePackages(packages: Set<String>) {
        if (AppSetting.replaceBlockedPackages(packages)) {
            BackupAutoScheduler.markDirtyAndSchedule(appContext)
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
 * 构建来源过滤设置页候选列表。
 *
 * @param blockedPackages 已保存的过滤包名集合，只用于勾选状态和置顶排序。
 * @param installedApps 主进程当前直读的安装应用列表，名称和安装图标模型优先用于展示。
 * @param historySourceApps 数据库中历史来源 App 缓存，图标路径必须真实存在才可展示。
 */
internal fun buildBlockedSourceAppCandidates(
    blockedPackages: Set<String>,
    installedApps: List<InstalledAppInfo>,
    historySourceApps: List<SourceAppData>,
): List<BlockedSourceAppCandidate> {
    /** 候选索引，LinkedHashMap 用于保持已过滤、安装列表、历史来源三段合并时的稳定覆盖顺序。 */
    val candidatesByPackage = linkedMapOf<String, BlockedSourceAppCandidate>()
    blockedPackages.forEach { packageName ->
        candidatesByPackage[packageName] = BlockedSourceAppCandidate(
            packageName = packageName,
            appName = null,
            icon = BlockedSourceAppIcon.None,
            installed = false,
            systemApp = false,
            launchableApp = false,
            savedBlocked = true,
            historySource = false,
        )
    }
    installedApps.forEach { installedApp ->
        /** 安装应用包名；异常空包名不参与展示。 */
        val packageName = installedApp.packageName.trim().takeIf { value -> value.isNotEmpty() } ?: return@forEach
        /** 已存在候选；用于保留已过滤标记和历史来源标记。 */
        val existing = candidatesByPackage[packageName]
        candidatesByPackage[packageName] = BlockedSourceAppCandidate(
            packageName = packageName,
            appName = installedApp.appName.takeIf { name -> name.isNotBlank() } ?: existing?.appName,
            icon = BlockedSourceAppIcon.Installed(packageName = packageName),
            installed = true,
            systemApp = installedApp.isSystemApp,
            launchableApp = installedApp.isLaunchableApp,
            savedBlocked = packageName in blockedPackages,
            historySource = existing?.historySource ?: false,
        )
    }
    historySourceApps.forEach { sourceApp ->
        /** 历史来源包名；空白来源不参与候选展示。 */
        val packageName = sourceApp.packageName.trim().takeIf { value -> value.isNotEmpty() } ?: return@forEach
        /** 已存在候选；用于保留已过滤标记和当前安装应用展示优先级。 */
        val existing = candidatesByPackage[packageName]
        /** 历史图标模型；只有真实文件、可读且非空时才可展示，避免坏路径触发 Coil 噪声。 */
        val historyIcon = buildHistorySourceIcon(sourceApp)
        candidatesByPackage[packageName] = BlockedSourceAppCandidate(
            packageName = packageName,
            appName = existing?.appName ?: sourceApp.appName.takeIf { name -> name.isNotBlank() },
            icon = when (existing?.icon) {
                is BlockedSourceAppIcon.Installed -> existing.icon
                is BlockedSourceAppIcon.HistoryFile -> existing.icon
                BlockedSourceAppIcon.None, null -> historyIcon
            },
            installed = existing?.installed ?: false,
            systemApp = existing?.systemApp ?: false,
            launchableApp = existing?.launchableApp ?: false,
            savedBlocked = packageName in blockedPackages,
            historySource = true,
        )
    }
    return candidatesByPackage.values.sortedWith(
        compareByDescending<BlockedSourceAppCandidate> { candidate -> candidate.savedBlocked }
            .thenByDescending { candidate -> candidate.defaultVisible }
            .thenBy { candidate -> candidate.displayName.lowercase(Locale.ROOT) }
            .thenBy { candidate -> candidate.packageName }
    )
}

/** 将历史来源图标缓存转换成 UI 图标模型；坏路径直接返回无图标。 */
private fun buildHistorySourceIcon(sourceApp: SourceAppData): BlockedSourceAppIcon {
    /** 历史图标路径；为空、文件缺失、不可读或零字节时不可交给 UI。 */
    val readableHistoryIconPath = sourceApp.iconPath
        ?.takeIf { path -> path.isNotBlank() }
        ?.takeIf { path -> File(path).isUsableCachedIconFile() }
        ?: return BlockedSourceAppIcon.None
    /** 历史图标 hash；可为空，存在时用于刷新 Coil 缓存 key。 */
    val readableHistoryIconHash = sourceApp.iconHash?.takeIf { hash -> hash.isNotBlank() }
    return BlockedSourceAppIcon.HistoryFile(
        iconPath = readableHistoryIconPath,
        iconHash = readableHistoryIconHash,
    )
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
    /** 最近一次成功读取或失败返回时间；仅用于页面会话自动读取节流和摘要展示。 */
    val lastLoadedAtMillis: Long = 0L,
    /** 本机安装应用读取失败的低敏原因码；为空表示当前没有错误。 */
    val unavailableReasonCode: String? = null,
    /** 是否在默认候选中显示系统应用；搜索时 UI 仍可命中系统应用。 */
    val showSystemApps: Boolean = false,
    /** 设置页搜索关键词，只影响候选展示。 */
    val searchQuery: String = "",
    /** 最近一次成功读取摘要；只保存在内存中，不进入数据库或备份。 */
    val lastLoadSummary: InstalledAppLoadSummary? = null,
    /** 当前内存安装应用数量；失败保留旧列表时用于头部摘要。 */
    val loadedAppCount: Int = 0,
)

/** 安装应用读取摘要。 */
data class InstalledAppLoadSummary(
    /** 本次读取到的 App 数量。 */
    val appCount: Int,
    /** 本次读取耗时毫秒。 */
    val elapsedMs: Long,
    /** 本次读取完成时间；只用于页面会话展示和节流。 */
    val loadedAtMillis: Long,
)

/** 来源过滤候选图标模型；区分当前安装图标、历史文件图标和无图标。 */
sealed interface BlockedSourceAppIcon {

    /** 当前安装应用图标；UI 可见行按包名通过 PackageManager 加载。 */
    data class Installed(
        /** 应用包名；图标加载唯一身份。 */
        val packageName: String,
    ) : BlockedSourceAppIcon

    /** 历史来源图标文件；只用于当前安装应用不可识别时兜底。 */
    data class HistoryFile(
        /** 历史来源图标缓存路径；已经过可读非空校验。 */
        val iconPath: String,
        /** 历史来源图标 hash；可为空，用于 Coil 缓存 key。 */
        val iconHash: String?,
    ) : BlockedSourceAppIcon

    /** 无可用图标；UI 使用通用 App 图标。 */
    data object None : BlockedSourceAppIcon
}

/**
 * 剪贴来源过滤候选项。
 *
 * 候选由已过滤包名、当前安装应用列表和历史来源合并而来；包名始终是稳定身份。
 */
data class BlockedSourceAppCandidate(
    /** 应用包名，作为过滤名单保存和匹配的唯一身份。 */
    val packageName: String,
    /** 用户可见应用名；未知或已卸载时为空并由 UI 回退包名。 */
    val appName: String?,
    /** UI 图标模型；不把当前安装图标塞进历史 iconPath 语义。 */
    val icon: BlockedSourceAppIcon,
    /** 当前安装列表是否能识别该包名。 */
    val installed: Boolean,
    /** 是否系统应用；默认列表隐藏系统应用，搜索时可展示。 */
    val systemApp: Boolean,
    /** 是否有启动入口；默认列表优先展示可启动应用。 */
    val launchableApp: Boolean,
    /** 当前是否已在过滤名单中。 */
    val savedBlocked: Boolean,
    /** 是否曾作为剪贴来源被记录过。 */
    val historySource: Boolean,
) {
    /** UI 展示标题；名称未知时回退包名，保证已保存但卸载的包名仍可管理。 */
    val displayName: String
        get() = appName?.takeIf { name -> name.isNotBlank() } ?: packageName

    /** 默认列表是否应该展示；系统应用默认隐藏，但已过滤、历史来源和搜索仍可展示。 */
    val defaultVisible: Boolean
        get() = savedBlocked || historySource || (launchableApp && !systemApp)
}
