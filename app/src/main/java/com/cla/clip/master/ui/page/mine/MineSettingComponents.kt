package com.cla.clip.master.ui.page.mine

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.widget.SettingSwitchRowState
import com.cla.clip.master.ui.widget.SettingSwitchListCard as SharedSettingSwitchListCard
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
        /** 当前权限项对应的共享开关行状态；单项成卡，避免多个权限挤在同一个卡片里。 */
        val rowState = SettingSwitchRowState(
            id = item.id,
            description = item.description,
            checked = item.checked,
            enabled = item.enabled
        )

        SharedSettingSwitchListCard(
            items = listOf(rowState),
            onItemCheckedChange = onItemCheckedChange,
        )
    }
}
