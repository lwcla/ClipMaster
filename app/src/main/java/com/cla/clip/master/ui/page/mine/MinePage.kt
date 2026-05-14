package com.cla.clip.master.ui.page.mine

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cla.clip.base.general.R
import com.cla.clip.base.general.utils.toPermissionSetting
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.navigation.DownloadHistoryRoute
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.theme.cardCornerShape
import com.cla.clip.master.ui.widget.CollapsingTitle
import com.cla.clip.shizuku.ShizukuUtils
import rikka.shizuku.Shizuku

/**
 * 我的页面。
 *
 * 当前承载权限说明和授权入口，后续增加设置项时也应保持 ViewModel 只发动作、页面执行系统跳转的边界。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinePage(
    mineVm: MineVm = hiltViewModel(),
    onNavigate: (route: Route) -> Unit
) {
    CollapsingTitle(stringResource(R.string.base_general_mine)) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { DownloadHistoryEntry(onNavigate = onNavigate) }
            item { Permission(mineVm = mineVm) }
        }
    }
}

/**
 * 下载记录入口。
 *
 * 放在“我的”页顶部，作为已下载视频和图片的统一管理入口；这里只负责导航，不直接读取下载数据。
 */
@Composable
private fun DownloadHistoryEntry(
    onNavigate: (route: Route) -> Unit,
) {
    val shape = cardCornerShape
    ElevatedCard(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable { onNavigate(DownloadHistoryRoute) }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.base_general_download_history),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.base_general_download_history_entry_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

/**
 * 可展开的设置卡片。
 *
 * 用于承载一组相关设置项；展开状态由调用方管理，便于后续把其他设置分组复用同一布局。
 */
@Composable
private fun ExpandableSettingCard(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    items: List<SettingSwitchItemUi>,
    onItemCheckedChange: (id: SettingSwitchItemUi.Id, checked: Boolean) -> Unit,
) {
    val shape = cardCornerShape
    ElevatedCard(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            // 标题行整体可点击，比只点击箭头更符合移动端设置项习惯。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )

                val rotation by animateFloatAsState(
                    targetValue = if (expanded && items.isNotEmpty()) 180f else 0f,
                    animationSpec = tween(durationMillis = 260),
                    label = "expand_icon_rotation"
                )

                // 旋转动画只表达展开状态，不改变布局尺寸，避免列表内容跳动。
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation)
                )
            }

            AnimatedVisibility(
                visible = expanded && items.isNotEmpty(),
                enter = expandVertically(animationSpec = tween(260)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(260)) + fadeOut(animationSpec = tween(120))
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 25.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items.forEachIndexed { index, item ->
                            SettingSwitchItemRow(
                                item = item,
                                onCheckedChange = { checked ->
                                    onItemCheckedChange(item.id, checked)
                                },
                            )
                            if (index != items.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置开关行。
 *
 * 行内只展示标题、说明和开关，不直接读取权限；调用方传入的 `onCheckedChange` 决定具体授权或设置跳转行为。
 */
@Composable
private fun SettingSwitchItemRow(
    item: SettingSwitchItemUi,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = item.checked,
            onCheckedChange = onCheckedChange,
            enabled = item.enabled,
        )
    }
}
