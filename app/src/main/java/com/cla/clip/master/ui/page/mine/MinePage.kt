package com.cla.clip.master.ui.page.mine

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.toPermissionSetting
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.widget.TopLevelTitleBar
import com.cla.clip.shizuku.ShizukuUtils
import rikka.shizuku.Shizuku

/**
 * 我的页面。
 *
 * 当前承载权限说明和授权入口，后续增加设置项时也应保持 ViewModel 只发动作、页面执行系统跳转的边界。
 */
@Composable
fun MinePage(
    mineVm: MineVm = hiltViewModel(),
    onNavigate: (route: Route) -> Unit,
    magnetFeatures: Set<MagnetFeatureEntry> = emptySet(),
    onOpenMagnetSearch: (MagnetFeatureEntry) -> Unit = {},
) {
    val foldedClipCount by mineVm.foldedClipCount.collectAsStateWithLifecycle()
    val recycleBinCount by mineVm.recycleBinCount.collectAsStateWithLifecycle()
    val clipItemQuickAction by mineVm.clipItemQuickAction.collectAsStateWithLifecycle()
    // 设置弹窗属于页面瞬时状态，配置值本身由 AppSetting/MMKV 持久化。
    var showClipItemActionDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        TopLevelTitleBar(title = stringResource(R.string.base_general_mine))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            item { BackupEntry(onNavigate = onNavigate) }
            magnetFeatures.sortedBy { it.featureId }.forEach { feature ->
                item(key = "magnet_feature_${feature.featureId}") {
                    feature.MineEntry(onOpenSearch = { onOpenMagnetSearch(feature) })
                }
            }
            item { DownloadHistoryEntry(onNavigate = onNavigate) }
            item { FoldedClipsEntry(foldedClipCount = foldedClipCount, onNavigate = onNavigate) }
            item { RecycleBinEntry(recycleBinCount = recycleBinCount, onNavigate = onNavigate) }
            item {
                ClipItemActionSettingEntry(
                    action = clipItemQuickAction,
                    onClick = { showClipItemActionDialog = true }
                )
            }
            item { Permission(mineVm = mineVm) }
        }
    }

    if (showClipItemActionDialog) {
        ClipItemActionSettingDialog(
            currentAction = clipItemQuickAction,
            onSelect = { action ->
                mineVm.updateClipItemQuickAction(action)
                showClipItemActionDialog = false
            },
            onDismiss = { showClipItemActionDialog = false }
        )
    }
}


/**
 * 权限说明模块。
 *
 * 负责展示权限开关、监听生命周期恢复和消费 ViewModel 发出的权限动作；权限真实状态始终从系统刷新。
 */
@Composable
private fun Permission(
    mineVm: MineVm,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { mineVm.refreshPermissionStatus() }
    )

    /**
     * 收集权限动作并在 UI 层执行。
     *
     * 权限弹窗和系统设置页跳转都需要 Context，因此 ViewModel 只负责判断状态并发出动作。
     */
    LaunchedEffect(mineVm) {
        mineVm.permissionActions.collect { action ->
            when (action) {
                MineVm.PermissionAction.RequestShizukuPermission -> ShizukuUtils.toConnect()
                MineVm.PermissionAction.OpenShizukuApp -> ShizukuUtils.toShizukuApp(context)
                MineVm.PermissionAction.DownloadShizuku -> ShizukuUtils.toDownloadApk(context)
                MineVm.PermissionAction.RequestNotificationPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        mineVm.refreshPermissionStatus()
                    }
                }

                MineVm.PermissionAction.OpenNotificationSettings -> {
                    context.toPermissionSetting(Manifest.permission.POST_NOTIFICATIONS)
                }

                MineVm.PermissionAction.OpenOverlaySettings -> {
                    context.toPermissionSetting(Manifest.permission.SYSTEM_ALERT_WINDOW)
                }
            }
        }
    }

    val items = listOf(
        // 每次重组重新生成展示项，确保字符串资源、系统权限状态和开关状态保持同步。
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Shizuku,
            title = stringResource(R.string.base_general_shizuku),
            description = stringResource(com.cla.clip.master.R.string.host_shizuku_service_require),
            checked = mineVm.shizukuChecked,
        ),
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Notice,
            title = stringResource(R.string.base_general_notice),
            description = stringResource(mineVm.notificationStatus.descriptionRes),
            checked = mineVm.notificationChecked,
        ),
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Overlay,
            title = stringResource(R.string.base_general_suspended_window),
            description = stringResource(com.cla.clip.master.R.string.host_suspended_window_permission_tip),
            checked = mineVm.overlayChecked,
        ),
    )

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从系统设置页或 Shizuku 应用返回后刷新状态，避免开关停留在旧值。
                mineVm.refreshPermissionStatus()
            }
        }

        val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            // Shizuku 授权结果由 SDK 回调，收到后立即刷新，避免等下一次 onResume。
            mineVm.refreshPermissionStatus()
        }

        owner.lifecycle.addObserver(observer)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    ExpandableSettingCard(
        title = stringResource(R.string.base_general_permission_description),
        expanded = mineVm.permissionExpanded,
        onToggleExpanded = { mineVm.togglePermissionExpanded() },
        items = items,
        onItemCheckedChange = mineVm::onItemCheckedChange,
    )
}

/**
 * 根据通知状态选择设置项说明文案。
 *
 * 同一个“通知”入口承载三种状态，避免拆成多个开关给普通用户造成困惑。
 */
private val MineVm.NotificationStatus.descriptionRes: Int
    get() = when (this) {
        MineVm.NotificationStatus.Enabled -> com.cla.clip.master.R.string.host_notification_permission_tip
        MineVm.NotificationStatus.RuntimeDenied -> com.cla.clip.master.R.string.host_notification_permission_denied_tip
        MineVm.NotificationStatus.SystemDisabled -> com.cla.clip.master.R.string.host_notification_system_disabled_tip
    }
