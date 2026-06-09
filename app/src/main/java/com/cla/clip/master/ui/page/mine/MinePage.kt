package com.cla.clip.master.ui.page.mine

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cla.clip.base.general.utils.toast
import com.cla.clip.feature.magnet.api.MagnetFeatureEntry
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens
import com.cla.clip.master.ui.widget.TopLevelPageScaffold
import com.cla.clip.shizuku.ShizukuUtils
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 我的页面。
 *
 * 当前承载权限项和授权入口，后续增加设置项时也应保持 ViewModel 只发动作、页面执行系统跳转的边界。
 */
@Composable
fun MinePage(
    mineVm: MineVm = hiltViewModel(),
    onNavigate: (route: Route) -> Unit,
    magnetFeatures: Set<MagnetFeatureEntry> = emptySet(),
    onOpenMagnetSearch: (MagnetFeatureEntry) -> Unit = {},
    visibleToUser: Boolean = true,
) {
    /** 折叠数据入口展示的轻量数量。 */
    val foldedClipCount by mineVm.foldedClipCount.collectAsStateWithLifecycle()
    /** 回收站入口展示的轻量数量。 */
    val recycleBinCount by mineVm.recycleBinCount.collectAsStateWithLifecycle()
    /** 普通剪贴 item 快捷动作当前设置。 */
    val clipItemQuickAction by mineVm.clipItemQuickAction.collectAsStateWithLifecycle()
    /** 更新入口当前展示状态。 */
    val appUpdateUiState by mineVm.appUpdateUiState.collectAsStateWithLifecycle()
    /** 当前页面 Context；只用于系统跳转和 toast。 */
    val context = LocalContext.current
    /** 页面级协程作用域；用于浏览器打开失败后的短时 toast。 */
    val coroutineScope = rememberCoroutineScope()
    // 设置弹窗属于页面瞬时状态，配置值本身由 AppSetting/MMKV 持久化。
    var showClipItemActionDialog by remember { mutableStateOf(false) }

    /**
     * 页面可见时触发自动检查更新。
     *
     * 主页 Pager 下只有当前可见的“我的”页才应该工作，避免后台 Tab 也去命中限频或发请求。
     */
    LaunchedEffect(mineVm, visibleToUser) {
        if (visibleToUser) {
            mineVm.checkUpdateAutomaticallyIfNeeded()
        }
    }

    /**
     * 收集更新链路的一次性外部动作。
     *
     * 打开浏览器需要页面层 Context；如果系统找不到处理链接的应用，则回退成 toast 提示。
     */
    LaunchedEffect(mineVm, context) {
        mineVm.appUpdateActions.collect { action ->
            when (action) {
                is MineVm.AppUpdateAction.OpenExternalLink -> {
                    /** 外部浏览器打开更新链接的标准 Intent。 */
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            coroutineScope.launch {
                                context.toast(R.string.base_general_app_update_no_browser)
                            }
                        }
                }
            }
        }
    }

    TopLevelPageScaffold(title = stringResource(R.string.base_general_mine)) { paddingValues ->
        /** 我的页分组列表的页面间距，来源于统一主题 token。 */
        val spacing = ClipMasterThemeTokens.tokens.spacing
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(top = spacing.small, bottom = spacing.large),
        ) {
            item { MineSectionHeader(title = stringResource(R.string.base_general_mine_permission_section)) }
            item { Permission(mineVm = mineVm) }

            item { MineSectionHeader(title = stringResource(R.string.base_general_mine_data_management_section)) }
            item { BackupEntry(onNavigate = onNavigate) }
            item { DownloadHistoryEntry(onNavigate = onNavigate) }
            item { FoldedClipsEntry(foldedClipCount = foldedClipCount, onNavigate = onNavigate) }
            item { RecycleBinEntry(recycleBinCount = recycleBinCount, onNavigate = onNavigate) }

            item { MineSectionHeader(title = stringResource(R.string.base_general_mine_feature_section)) }
            item {
                AppUpdateEntry(
                    state = appUpdateUiState,
                    onClick = mineVm::checkUpdateManually,
                )
            }
            magnetFeatures.sortedBy { it.featureId }.forEach { feature ->
                item(key = "magnet_feature_${feature.featureId}") {
                    feature.MineEntry(onOpenSearch = { onOpenMagnetSearch(feature) })
                }
            }

            item { MineSectionHeader(title = stringResource(R.string.base_general_mine_settings_section)) }
            item {
                ClipItemActionSettingEntry(
                    action = clipItemQuickAction,
                    onClick = { showClipItemActionDialog = true }
                )
            }
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

    /** 更新对话框只在 ViewModel 明确给出状态时展示。 */
    appUpdateUiState.dialog?.let { dialogState ->
        AppUpdateDialog(
            state = dialogState,
            onOpenLink = mineVm::openUpdateLink,
            onDismiss = mineVm::dismissUpdateDialog,
        )
    }
}

/** 我的页分组标题，用短标签降低入口列表的平铺感。 */
@Composable
private fun MineSectionHeader(title: String) {
    /** 分组标题间距，保证标题和入口卡片形成稳定节奏。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    Text(
        text = title,
        modifier = Modifier.padding(start = spacing.large, top = spacing.medium, end = spacing.large, bottom = spacing.tiny),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}


/**
 * 权限设置项模块。
 *
 * 负责展示权限开关、监听生命周期恢复和消费 ViewModel 发出的权限动作；权限真实状态始终从系统刷新。
 */
@Composable
private fun Permission(
    mineVm: MineVm,
) {
    /** 当前页面 Context；用于打开系统设置页和 Shizuku 应用。 */
    val context = LocalContext.current
    /** 当前生命周期宿主；用于页面恢复时刷新权限状态。 */
    val owner = LocalLifecycleOwner.current
    /** Android 13+ 通知运行时权限请求器。 */
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
            }
        }
    }

    /** 权限设置项展示列表；每次重组重建，确保文案和系统真实状态保持同步。 */
    val items = listOf(
        // 每次重组重新生成展示项，确保字符串资源、系统权限状态和开关状态保持同步。
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Shizuku,
            title = stringResource(com.cla.clip.master.R.string.host_shizuku_permission_title),
            description = stringResource(com.cla.clip.master.R.string.host_shizuku_service_require),
            checked = mineVm.shizukuChecked,
        ),
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Notice,
            title = stringResource(com.cla.clip.master.R.string.host_notification_permission_title),
            description = stringResource(mineVm.notificationStatus.descriptionRes),
            checked = mineVm.notificationChecked,
        ),
    )

    /**
     * 监听页面恢复和 Shizuku 授权回调。
     *
     * 两条链路都可能在页面外部改变系统真实权限，因此回到页面后要立即重新刷新展示状态。
     */
    DisposableEffect(owner) {
        /** 页面回到前台时刷新系统权限状态的生命周期观察器。 */
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从系统设置页或 Shizuku 应用返回后刷新状态，避免开关停留在旧值。
                mineVm.refreshPermissionStatus()
            }
        }

        /** Shizuku SDK 返回授权结果时触发的监听器。 */
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

    PermissionSettingItems(
        items = items,
        onItemCheckedChange = mineVm::onItemCheckedChange,
    )
}

/**
 * 根据通知状态选择设置项说明文案。
 *
 * 同一个“通知”入口承载三种状态，强调它只影响提醒展示，不影响剪贴保存。
 */
private val MineVm.NotificationStatus.descriptionRes: Int
    get() = when (this) {
        MineVm.NotificationStatus.Enabled -> com.cla.clip.master.R.string.host_notification_permission_tip
        MineVm.NotificationStatus.RuntimeDenied -> com.cla.clip.master.R.string.host_notification_permission_denied_tip
        MineVm.NotificationStatus.SystemDisabled -> com.cla.clip.master.R.string.host_notification_system_disabled_tip
    }
