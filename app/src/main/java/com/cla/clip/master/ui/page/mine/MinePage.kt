package com.cla.clip.master.ui.page.mine

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
import com.cla.clip.base.general.utils.hasNotificationPermission
import com.cla.clip.base.general.utils.hasOverlayPermission
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

    val items = listOf(
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Shizuku,
            title = stringResource(R.string.base_general_shizuku),
            description = stringResource(com.cla.clip.master.R.string.host_shizuku_service_require),
            checked = ShizukuUtils.isConnected(context),
        ),
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Notice,
            title = stringResource(R.string.base_general_notice),
            description = stringResource(com.cla.clip.master.R.string.host_notification_permission_tip),
            checked = context.hasNotificationPermission(),
        ),
        SettingSwitchItemUi(
            id = SettingSwitchItemUi.Id.Permission.Notice,
            title = stringResource(R.string.base_general_suspended_window),
            description = stringResource(com.cla.clip.master.R.string.host_suspended_window_permission_tip),
            checked = context.hasOverlayPermission(),
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