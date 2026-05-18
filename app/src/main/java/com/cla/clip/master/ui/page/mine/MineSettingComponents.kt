package com.cla.clip.master.ui.page.mine

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.widget.ExpandableSettingCard as SharedExpandableSettingCard
import com.cla.clip.master.ui.widget.SettingSwitchRowState
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
 * 可展开的设置卡片。
 *
 * 用于承载一组相关设置项；展开状态由调用方管理，便于后续把其他设置分组复用同一布局。
 */
@Composable
internal fun ExpandableSettingCard(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    items: List<SettingSwitchItemUi>,
    onItemCheckedChange: (id: SettingSwitchItemUi.Id, checked: Boolean) -> Unit,
) {
    SharedExpandableSettingCard(
        title = title,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        items = items.map { item ->
            SettingSwitchRowState(
                id = item.id,
                title = item.title,
                description = item.description,
                checked = item.checked,
                enabled = item.enabled
            )
        },
        onItemCheckedChange = onItemCheckedChange,
    )
}
