package com.cla.clip.master.ui.page.mine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermIdentity
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.widget.ExpandableSettingSwitchEntryCard

/**
 * 权限设置项卡片列表。
 *
 * 每个权限项都独立成卡，点击卡片仅展开说明，右侧开关才发送权限动作；权限刷新和系统跳转由页面负责。
 */
@Composable
internal fun PermissionSettingItems(
    items: List<SettingSwitchItemUi>,
    onItemCheckedChange: (id: SettingSwitchItemUi.Id, checked: Boolean) -> Unit,
) {
    items.forEach { item ->
        /** 当前权限项的稳定业务 id，用于保持阅读状态身份并把开关操作映射回 ViewModel。 */
        val itemId = item.id
        // 按权限身份保存各卡片的阅读状态，后续调整顺序也不串用展开状态。
        key(itemId) {
            ExpandableSettingSwitchEntryCard(
                title = item.title,
                description = item.description,
                checked = item.checked,
                enabled = item.enabled,
                icon = { PermissionSettingIcon(id = itemId) },
                onCheckedChange = { checked ->
                    /** 用户点击右侧开关后请求的目标状态；ViewModel 按系统真实状态决定动作。 */
                    val requestedChecked = checked
                    onItemCheckedChange(itemId, requestedChecked)
                },
            )
        }
    }
}

/**
 * 权限入口卡片的语义图标。
 *
 * 当前不引入 Shizuku 官方品牌图标；Shizuku 使用权限/身份图标，通知使用通知图标，避免新增资源授权和深浅色维护成本。
 *
 * @param id 权限项稳定 id，用于选择当前卡片应展示的语义图标。
 */
@Composable
private fun PermissionSettingIcon(id: SettingSwitchItemUi.Id) {
    Icon(
        imageVector = when (id) {
            SettingSwitchItemUi.Id.Permission.Shizuku -> Icons.Default.PermIdentity
            SettingSwitchItemUi.Id.Permission.Notice -> Icons.Default.Notifications
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary
    )
}
