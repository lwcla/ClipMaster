package com.cla.clip.master.ui.page.mine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermIdentity
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.widget.SettingSwitchEntryCard
import com.cla.clip.master.ui.widget.SingleChoiceDialog
import com.cla.clip.master.ui.widget.SingleChoiceOption

/**
 * 普通剪贴 item 快捷动作选择弹窗。
 *
 * 选项采用五选一并在点击后立即保存；“无”代表彻底关闭快捷动作区，而不是只关闭回调。
 */
@Composable
internal fun ClipItemActionSettingDialog(
    currentAction: ClipItemQuickAction,
    onSelect: (ClipItemQuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    SingleChoiceDialog(
        title = stringResource(R.string.base_general_clip_item_quick_action_setting),
        description = stringResource(R.string.base_general_clip_item_quick_action_setting_desc),
        options = clipItemQuickActionOptions.map { action ->
            SingleChoiceOption(
                value = action,
                title = action.labelText()
            )
        },
        selectedValue = currentAction,
        onSelect = onSelect,
        onDismiss = onDismiss,
        dismissText = stringResource(R.string.base_general_cancel),
    )
}

/**
 * 快捷动作在设置界面的展示顺序。
 *
 * 新增 item 操作时需要同步扩展这里、枚举、字符串资源、共享 item 映射和方案文档。
 */
private val clipItemQuickActionOptions = listOf(
    ClipItemQuickAction.Copy,
    ClipItemQuickAction.Pin,
    ClipItemQuickAction.Delete,
    ClipItemQuickAction.Fold,
    ClipItemQuickAction.None,
)

/**
 * 权限设置项卡片列表。
 *
 * 每个权限项都独立成卡；权限刷新、系统跳转和授权动作仍由页面与 ViewModel 负责。
 */
@Composable
internal fun PermissionSettingItems(
    items: List<SettingSwitchItemUi>,
    onItemCheckedChange: (id: SettingSwitchItemUi.Id, checked: Boolean) -> Unit,
) {
    items.forEach { item ->
        /** 当前权限项的稳定业务 id，用于把共享卡片点击映射回 ViewModel 权限动作。 */
        val itemId = item.id
        SettingSwitchEntryCard(
            title = item.title,
            description = item.description,
            checked = item.checked,
            enabled = item.enabled,
            icon = { PermissionSettingIcon(id = itemId) },
            onCheckedChange = { checked ->
                /** 用户点击卡片或开关后请求切换到的目标状态；ViewModel 会按系统真实状态决定动作。 */
                val requestedChecked = checked
                onItemCheckedChange(itemId, requestedChecked)
            },
        )
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
