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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import com.cla.clip.master.ui.navigation.Route
import com.cla.clip.master.ui.theme.cardCornerShape
import com.cla.clip.shizuku.ShizukuUtils
import rikka.shizuku.Shizuku

/** 我的页面 */
@Composable
fun MinePage(
    mineVm: MineVm = hiltViewModel(),
    onNavigate: (route: Route) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Permission(mineVm = mineVm)
    }
}

/** 权限说明模块 */
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
                // 在 onResume 时检查 Shizuku 状态
                mineVm.refreshPermissionStatus()
            }
        }

        val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
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

/** 通用：可展开设置卡片 */
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
            // 标题行
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

                // 这里用旋转动画也可以；先用静态图标切换更直观
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

/** 通用：开关子项行 */
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
