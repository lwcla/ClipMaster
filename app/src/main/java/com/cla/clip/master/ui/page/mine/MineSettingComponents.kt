package com.cla.clip.master.ui.page.mine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.base.general.config.ClipItemQuickAction
import com.cla.clip.master.entity.SettingSwitchItemUi
import com.cla.clip.master.ui.widget.ClipMasterCardDefaults
import com.cla.clip.master.ui.widget.ClipMasterGestureCard

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.base_general_clip_item_quick_action_setting))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.base_general_clip_item_quick_action_setting_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                clipItemQuickActionOptions.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(action) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = action == currentAction,
                            onClick = { onSelect(action) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = action.labelText(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.base_general_cancel))
            }
        }
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
    ClipMasterGestureCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentPadding = ClipMasterCardDefaults.ZeroContentPadding,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
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
