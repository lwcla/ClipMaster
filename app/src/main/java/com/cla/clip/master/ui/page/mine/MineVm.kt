package com.cla.clip.master.ui.page.mine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.hasNotificationRuntimePermission
import com.cla.clip.base.general.utils.hasOverlayPermission
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.shizuku.ShizukuStatus
import com.cla.clip.shizuku.ShizukuUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class MineVm @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "MineVm"
    }

    var permissionExpanded by mutableStateOf(false)
        private set

    var shizukuChecked by mutableStateOf(false)
        private set
    var notificationChecked by mutableStateOf(false)
        private set
    var notificationStatus by mutableStateOf(NotificationStatus.RuntimeDenied)
        private set
    var overlayChecked by mutableStateOf(false)
        private set

    private val _permissionActions = MutableSharedFlow<PermissionAction>(extraBufferCapacity = 1)
    val permissionActions = _permissionActions.asSharedFlow()

    init {
        // 初始化时读取一次系统权限状态，确保页面首次展示的开关状态准确。
        refreshPermissionStatus()
    }

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
        notificationStatus = getNotificationStatus()
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
    private fun getNotificationStatus(): NotificationStatus {
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

    private fun emitPermissionAction(action: PermissionAction) {
        _permissionActions.tryEmit(action)
    }

    sealed class PermissionAction {
        data object RequestShizukuPermission : PermissionAction()
        data object OpenShizukuApp : PermissionAction()
        data object DownloadShizuku : PermissionAction()
        data object RequestNotificationPermission : PermissionAction()
        data object OpenNotificationSettings : PermissionAction()
        data object OpenOverlaySettings : PermissionAction()
    }

    enum class NotificationStatus {
        Enabled,
        RuntimeDenied,
        SystemDisabled,
    }
}
