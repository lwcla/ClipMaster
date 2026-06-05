package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.config.AppSetting
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.hasNotificationRuntimePermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logI
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.update.AppUpdateCheckResult
import com.cla.clip.master.update.AppUpdateCheckTrigger
import com.cla.clip.master.update.AppUpdateChecker
import com.cla.clip.master.update.AppUpdateConfigFactory
import com.cla.clip.master.update.AppUpdateLink
import com.cla.clip.master.work.BackupAutoScheduler
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 我的页 ViewModel。
 *
 * 负责汇总 Shizuku 和通知权限状态，并把用户点击转换为一次性权限动作。
 * 实际系统弹窗或设置页跳转由 UI 层执行，避免 ViewModel 持有 Activity 结果 API。
 */
@HiltViewModel
class MineVm @Inject constructor(
    /** 应用级 Context，仅用于读取系统权限状态，不持有页面实例。 */
    @param:ApplicationContext private val appContext: Context,

    /** 剪贴数据仓库使用 Lazy，避免我的页只查看权限时提前创建数据库依赖。 */
    private val clipRepository: Lazy<ClipRepository>,

    /** 更新检查器；负责远端抓取、解析、回退和失败兜底。 */
    private val appUpdateChecker: AppUpdateChecker,

    /** 更新配置工厂；按当前构建生成数据源、渠道和发布页配置。 */
    private val appUpdateConfigFactory: AppUpdateConfigFactory,
) : ViewModel() {

    companion object {
        private const val TAG = "MineVm"
    }

    /** Shizuku 服务是否已连接，连接成功才代表可以使用跨进程剪贴板监听。 */
    var shizukuChecked by mutableStateOf(false)
        private set

    /** 通知入口开关展示状态，只有运行时权限和系统通知总开关都可用时才为 true。 */
    var notificationChecked by mutableStateOf(false)
        private set

    /** 通知权限的细分状态，用于区分运行时拒绝和系统通知总开关关闭。 */
    var notificationStatus by mutableStateOf(NotificationStatus.RuntimeDenied)
        private set

    /**
     * 权限点击产生的一次性动作。
     *
     * 使用 SharedFlow 避免状态恢复时重复弹权限框或重复打开系统设置页。
     */
    private val _permissionActions = MutableSharedFlow<PermissionAction>(extraBufferCapacity = 1)

    /** 页面订阅的权限动作流。 */
    val permissionActions = _permissionActions.asSharedFlow()

    /** 延迟创建的更新检查配置；只有进入更新链路时才读取 BuildConfig 和发布页配置。 */
    private val appUpdateConfig by lazy(appUpdateConfigFactory::create)

    /** “我的”页更新入口展示状态；包含当前版本、检查中标记和结果弹窗状态。 */
    private val _appUpdateUiState = MutableStateFlow(
        AppUpdateUiState(
            currentVersionName = appUpdateConfig.currentApp.versionName,
            currentVersionCode = appUpdateConfig.currentApp.versionCode,
        )
    )

    /** 页面订阅的更新入口 UI 状态流。 */
    val appUpdateUiState = _appUpdateUiState.asStateFlow()

    /** 更新链路产生的一次性外部动作，例如打开浏览器。 */
    private val _appUpdateActions = MutableSharedFlow<AppUpdateAction>(extraBufferCapacity = 1)

    /** 页面订阅的更新外部动作流。 */
    val appUpdateActions = _appUpdateActions.asSharedFlow()

    /**
     * 折叠记录数量。
     *
     * “我的”入口只需要显示数量，使用 DAO 的 COUNT Flow，避免为了统计加载折叠分页列表。
     */
    val foldedClipCount = clipRepository.get()
        .observeFoldedClipCount()
        .stateIn(
            CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
            SharingStarted.WhileSubscribed(5_000),
            0
        )

    /**
     * 回收站记录数量。
     *
     * 与折叠数量一样使用轻量 COUNT Flow，只为“我的”入口展示数字，不加载回收站分页数据。
     */
    val recycleBinCount = clipRepository.get()
        .observeRecycleBinCount()
        .stateIn(
            CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO),
            SharingStarted.WhileSubscribed(5_000),
            0
        )

    /**
     * 普通剪贴 item 快捷动作设置。
     *
     * 直接暴露 AppSetting 的 StateFlow，让“我的”页修改后普通列表和普通搜索能立即跟随刷新。
     */
    val clipItemQuickAction = AppSetting.clipItemQuickActionFlow

    init {
        // 初始化时读取一次系统权限状态，确保页面首次展示的开关状态准确。
        refreshPermissionStatus()
    }

    /**
     * 保存普通剪贴 item 快捷动作。
     *
     * 这里不做页面范围判断；普通列表/普通搜索负责消费，折叠列表和回收站固定忽略该设置。
     */
    fun updateClipItemQuickAction(action: ClipItemQuickAction) {
        AppSetting.clipItemQuickAction = action
        BackupAutoScheduler.markDirtyAndSchedule(appContext)
    }

    /** 用户手动点击“检查更新”时触发完整检查，并允许展示“已是最新”的正向提示。 */
    fun checkUpdateManually() {
        startUpdateCheck(trigger = AppUpdateCheckTrigger.Manual, forceCheck = true)
    }

    /** 页面可见后执行轻量自动检查；命中限频时静默返回。 */
    fun checkUpdateAutomaticallyIfNeeded() {
        startUpdateCheck(trigger = AppUpdateCheckTrigger.Auto, forceCheck = false)
    }

    /** 关闭当前更新结果弹窗。 */
    fun dismissUpdateDialog() {
        _appUpdateUiState.update { it.copy(dialog = null) }
    }

    /** 把下载源或发布页跳转成一次性外部动作，由页面层负责真正打开浏览器。 */
    fun openUpdateLink(link: AppUpdateLink) {
        logI(TAG) { "打开更新外部链接 linkType=${link.logType}" }
        _appUpdateActions.tryEmit(AppUpdateAction.OpenExternalLink(link.url))
    }

    /**
     * 启动一次更新检查。
     *
     * 先做“正在检查中”互斥，再做自动检查限频；只有真的进入网络检查时才切换 UI 状态。
     */
    private fun startUpdateCheck(trigger: AppUpdateCheckTrigger, forceCheck: Boolean) {
        if (_appUpdateUiState.value.checking) {
            logD(TAG) { "检查更新已在进行中 trigger=${trigger.code}" }
            return
        }

        /** 本次检查开始时间；只用于自动检查限频判断。 */
        val now = System.currentTimeMillis()
        /** 自动检查是否命中 24 小时限频；手动检查永远绕过。 */
        val throttleHit = !forceCheck && now - AppSetting.appUpdateLastCheckAt < AppSetting.APP_UPDATE_AUTO_CHECK_INTERVAL_MILLIS
        if (throttleHit) {
            logD(TAG) {
                /** 距离下一次允许自动检查还剩的分钟数，日志中只用于排障。 */
                val nextCheckAfterMinutes = TimeUnit.MILLISECONDS.toMinutes(
                    AppSetting.APP_UPDATE_AUTO_CHECK_INTERVAL_MILLIS - (now - AppSetting.appUpdateLastCheckAt)
                ).coerceAtLeast(0L)
                "自动检查更新命中限频 trigger=${trigger.code} nextCheckAfterMinutes=$nextCheckAfterMinutes"
            }
            return
        }

        viewModelScope.launch {
            _appUpdateUiState.update { it.copy(checking = true) }
            /** 本次更新检查结果；成功、已最新和失败都会统一映射成对话框状态。 */
            val result = appUpdateChecker.check(
                config = appUpdateConfig,
                trigger = trigger,
                forceCheck = forceCheck,
                throttleHit = false,
            )
            AppSetting.appUpdateLastCheckAt = System.currentTimeMillis()
            _appUpdateUiState.update { state ->
                state.copy(
                    checking = false,
                    dialog = result.toDialogState(showPositiveResult = forceCheck),
                )
            }
        }
    }

    /**
     * 把检查结果映射成页面弹窗状态。
     *
     * 自动检查默认静默：没有新版本时不弹“已是最新”，纯失败也不打扰用户；
     * 但如果失败结果里带着 fallback 发布页，仍允许给用户一个手动查看入口。
     */
    private fun AppUpdateCheckResult.toDialogState(showPositiveResult: Boolean): AppUpdateDialogState? {
        return when (this) {
            is AppUpdateCheckResult.UpdateAvailable -> AppUpdateDialogState.UpdateAvailable(info)
            is AppUpdateCheckResult.UpToDate -> {
                if (showPositiveResult) {
                    AppUpdateDialogState.UpToDate(versionName = versionName, versionCode = versionCode)
                } else {
                    null
                }
            }

            is AppUpdateCheckResult.Failed -> {
                if (showPositiveResult || fallbackReleasePage != null) {
                    AppUpdateDialogState.CheckUnavailable(reason = reason, fallbackReleasePage = fallbackReleasePage)
                } else {
                    null
                }
            }
        }
    }

    /**
     * 刷新 Shizuku 和通知权限的真实状态。
     *
     * 开关本身不保存用户意图，只展示系统当前状态，避免用户从设置页返回后 UI 状态不一致。
     */
    fun refreshPermissionStatus() {
        shizukuChecked = ShizukuUtils.isConnected(appContext)
        notificationStatus = resolveNotificationStatus()
        notificationChecked = notificationStatus == NotificationStatus.Enabled
        logD(TAG) {
            "refreshPermissionStatus shizukuChecked=$shizukuChecked " +
                "notificationStatus=$notificationStatus notificationChecked=$notificationChecked"
        }
    }

    /**
     * 处理权限开关点击。
     *
     * 如果当前权限已经开启，就跳转到对应设置页面让用户关闭；
     * 如果当前权限尚未开启，就主动发起申请或跳转到对应授权页面。
     */
    fun onItemCheckedChange(id: SettingSwitchItemUi.Id, checked: Boolean) {
        /** 这里忽略 checked 入参本身，只根据系统真实状态决定下一步动作，避免 UI 预设值误导逻辑。 */
        when (id) {
            SettingSwitchItemUi.Id.Permission.Shizuku -> handleShizukuClick()
            SettingSwitchItemUi.Id.Permission.Notice -> handleNotificationClick()
        }
    }

    /**
     * 根据 Shizuku 当前状态决定下一步动作。
     *
     * Shizuku 已连接时无法在应用内直接关闭，只能跳转到 Shizuku 应用让用户手动处理；
     * 未连接时根据具体原因进入下载、激活、更新或授权流程。
     */
    private fun handleShizukuClick() {
        when (ShizukuUtils.checkStatus(appContext)) {
            is ShizukuStatus.Connected -> emitPermissionAction(PermissionAction.OpenShizukuApp)
            is ShizukuStatus.Disconnect.NotInstalled -> emitPermissionAction(PermissionAction.DownloadShizuku)
            is ShizukuStatus.Disconnect.ServiceNotAlive -> emitPermissionAction(PermissionAction.OpenShizukuApp)
            is ShizukuStatus.Disconnect.VersionTooLow -> emitPermissionAction(PermissionAction.DownloadShizuku)
            is ShizukuStatus.Disconnect.NotGranted -> emitPermissionAction(PermissionAction.RequestShizukuPermission)
        }
    }

    /**
     * 根据通知权限当前状态决定下一步动作。
     *
     * 通知权限已开启时跳转系统通知设置用于关闭；未开启时触发运行时权限申请或进入系统通知设置。
     */
    private fun handleNotificationClick() {
        if (appContext.hasNotificationPermission()) {
            emitPermissionAction(PermissionAction.OpenNotificationSettings)
        } else if (!appContext.hasNotificationRuntimePermission()) {
            emitPermissionAction(PermissionAction.RequestNotificationPermission)
        } else {
            emitPermissionAction(PermissionAction.OpenNotificationSettings)
        }
    }

    /**
     * 拆分通知的真实状态。
     *
     * 通知权限只影响剪贴保存、下载和 Shizuku 状态提醒展示；关闭后剪贴仍会继续入库。
     */
    private fun resolveNotificationStatus(): NotificationStatus {
        return when {
            !appContext.hasNotificationRuntimePermission() -> NotificationStatus.RuntimeDenied
            appContext.hasNotificationPermission() -> NotificationStatus.Enabled
            else -> NotificationStatus.SystemDisabled
        }
    }

    /** 发送权限动作事件，缓冲区满时丢弃旧动作，避免连续点击造成多个系统页面叠加。 */
    private fun emitPermissionAction(action: PermissionAction) {
        _permissionActions.tryEmit(action)
    }

    /**
     * 我的页权限入口需要执行的一次性动作。
     *
     * ViewModel 只描述动作类型，具体启动权限弹窗、打开系统设置或跳转 Shizuku 应用由 Composable 完成。
     */
    sealed class PermissionAction {
        /** 请求 Shizuku 授权弹窗。 */
        data object RequestShizukuPermission : PermissionAction()

        /** 打开 Shizuku 应用，用于启动服务或关闭已连接服务。 */
        data object OpenShizukuApp : PermissionAction()

        /** 跳转到 Shizuku 下载页面。 */
        data object DownloadShizuku : PermissionAction()

        /** 请求 Android 13+ 通知运行时权限。 */
        data object RequestNotificationPermission : PermissionAction()

        /** 打开系统通知设置页。 */
        data object OpenNotificationSettings : PermissionAction()

    }

    /**
     * 通知权限细分状态。
     *
     * Android 13+ 的运行时权限和系统通知总开关语义不同，需要分开展示，方便用户知道该去哪里恢复。
     */
    enum class NotificationStatus {
        /** 通知运行时权限和系统通知总开关都可用。 */
        Enabled,

        /** Android 13+ POST_NOTIFICATIONS 运行时权限被拒绝。 */
        RuntimeDenied,

        /** 运行时权限可用，但系统通知总开关被关闭。 */
        SystemDisabled,
    }

    /** 更新入口需要页面执行的一次性外部动作。 */
    sealed class AppUpdateAction {
        /** 打开外部浏览器或其他能处理下载链接的应用。 */
        data class OpenExternalLink(val url: String) : AppUpdateAction()
    }
}
