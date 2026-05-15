package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.repository.ClipRepository
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.hasNotificationRuntimePermission
import com.cla.clip.base.general.utils.hasOverlayPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 我的页 ViewModel。
 *
 * 负责汇总 Shizuku、通知和悬浮窗权限状态，并把用户点击转换为一次性权限动作。
 * 实际系统弹窗或设置页跳转由 UI 层执行，避免 ViewModel 持有 Activity 结果 API。
 */
@HiltViewModel
class MineVm @Inject constructor(
    /** 应用级 Context，仅用于读取系统权限状态，不持有页面实例。 */
    @param:ApplicationContext private val appContext: Context,

    /** 剪贴数据仓库使用 Lazy，避免我的页只查看权限时提前创建数据库依赖。 */
    private val clipRepository: Lazy<ClipRepository>,
) : ViewModel() {

    companion object {
        private const val TAG = "MineVm"
    }

    /** 权限说明卡片是否展开；属于纯 UI 状态，页面重建后恢复默认折叠。 */
    var permissionExpanded by mutableStateOf(false)
        private set

    /** Shizuku 服务是否已连接，连接成功才代表可以使用跨进程剪贴板监听。 */
    var shizukuChecked by mutableStateOf(false)
        private set

    /** 通知入口开关展示状态，只有运行时权限和系统通知总开关都可用时才为 true。 */
    var notificationChecked by mutableStateOf(false)
        private set

    /** 通知权限的细分状态，用于区分运行时拒绝和系统通知总开关关闭。 */
    var notificationStatus by mutableStateOf(NotificationStatus.RuntimeDenied)
        private set

    /** 悬浮窗权限是否已授予，当前只负责展示和跳转设置页。 */
    var overlayChecked by mutableStateOf(false)
        private set

    /**
     * 权限点击产生的一次性动作。
     *
     * 使用 SharedFlow 避免状态恢复时重复弹权限框或重复打开系统设置页。
     */
    private val _permissionActions = MutableSharedFlow<PermissionAction>(extraBufferCapacity = 1)

    /** 页面订阅的权限动作流。 */
    val permissionActions = _permissionActions.asSharedFlow()

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

    init {
        // 初始化时读取一次系统权限状态，确保页面首次展示的开关状态准确。
        refreshPermissionStatus()
    }

    /** 展开或收起权限说明卡片。 */
    fun togglePermissionExpanded() {
        permissionExpanded = !permissionExpanded
    }

    /**
     * 刷新三类权限的真实状态。
     *
     * 开关本身不保存用户意图，只展示系统当前状态，避免用户从设置页返回后 UI 状态不一致。
     */
    fun refreshPermissionStatus() {
        shizukuChecked = ShizukuUtils.isConnected(appContext)
        notificationStatus = resolveNotificationStatus()
        notificationChecked = notificationStatus == NotificationStatus.Enabled
        overlayChecked = appContext.hasOverlayPermission()
        logD(TAG) { "refreshPermissionStatus shizukuChecked=$shizukuChecked notificationStatus=$notificationStatus notificationChecked=$notificationChecked overlayChecked=$overlayChecked" }
    }

    /**
     * 处理权限开关点击。
     *
     * 如果当前权限已经开启，就跳转到对应设置页面让用户关闭；
     * 如果当前权限尚未开启，就主动发起申请或跳转到对应授权页面。
     */
    fun onItemCheckedChange(id: SettingSwitchItemUi.Id, checked: Boolean) {
        when (id) {
            SettingSwitchItemUi.Id.Permission.Shizuku -> handleShizukuClick()
            SettingSwitchItemUi.Id.Permission.Notice -> handleNotificationClick()
            SettingSwitchItemUi.Id.Permission.Overlay -> handleOverlayClick()
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
     * 通知权限已开启时跳转系统通知设置用于关闭；未开启时触发运行时权限申请。
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
     * 运行时权限关闭会影响剪贴板监听链路；系统通知总开关关闭只影响通知展示，不应阻断前台服务启动。
     */
    private fun resolveNotificationStatus(): NotificationStatus {
        return when {
            !appContext.hasNotificationRuntimePermission() -> NotificationStatus.RuntimeDenied
            appContext.hasNotificationPermission() -> NotificationStatus.Enabled
            else -> NotificationStatus.SystemDisabled
        }
    }

    /**
     * 根据悬浮窗权限当前状态决定下一步动作。
     *
     * 悬浮窗权限没有标准运行时弹窗，开启和关闭都需要进入系统悬浮窗设置页。
     */
    private fun handleOverlayClick() {
        emitPermissionAction(PermissionAction.OpenOverlaySettings)
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

        /** 打开系统悬浮窗设置页。 */
        data object OpenOverlaySettings : PermissionAction()
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
}
