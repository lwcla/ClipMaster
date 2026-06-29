package com.cla.clip.master.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.widget.DeleteActionIcon

/**
 * 通用单选项模型。
 *
 * 只表达可见标题和稳定值；调用方负责把业务枚举、设置项或筛选项转换成该模型。
 */
internal data class SingleChoiceOption<T>(
    /** 选项对应的业务值，点击后原样回传给调用方。 */
    val value: T,

    /** 选项标题，必须由调用方资源化后传入。 */
    val title: String,
)

/**
 * 通用单选弹窗。
 *
 * 适用于设置项、筛选项等即时选择场景；说明文案可选，避免公共组件绑定具体业务语境。
 */
@Composable
internal fun <T> SingleChoiceDialog(
    title: String,
    options: List<SingleChoiceOption<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    dismissText: String,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                options.forEach { option ->
                    SingleChoiceRow(
                        title = option.title,
                        selected = option.value == selectedValue,
                        onClick = { onSelect(option.value) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissText)
            }
        }
    )
}

/**
 * 通用单选行。
 *
 * 行整体可点，RadioButton 也触发同一个回调；常用于 Dialog、Sheet 或设置列表中的单选选项。
 */
@Composable
internal fun SingleChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 通用复选列表项模型。
 *
 * `id` 必须稳定，供 LazyColumn key 使用；标题和副标题由页面资源化或格式化后传入。
 */
internal data class SelectableListItemState<Id>(
    /** 选项稳定身份，通常是数据库主键、包名或业务 code。 */
    val id: Id,

    /** 主标题。 */
    val title: String,

    /** 可选副标题，用于显示包名、说明或补充信息。 */
    val subtitle: String? = null,

    /** 当前是否被选中。 */
    val selected: Boolean,
)

/**
 * 通用复选列表底部弹层。
 *
 * 组件提供标题、可选全选控制、列表、错误提示和确认/取消动作；选择状态由调用方维护或在薄适配层中维护。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <Id> SelectableListBottomSheet(
    title: String,
    items: List<SelectableListItemState<Id>>,
    onDismiss: () -> Unit,
    onToggleItem: (Id) -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    confirmText: String,
    cancelText: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    showSelectAll: Boolean = false,
    selectAllChecked: Boolean = false,
    selectAllEnabled: Boolean = true,
    selectAllText: String? = null,
    onToggleSelectAll: (() -> Unit)? = null,
    errorText: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
                .padding(bottom = 24.dp)
        ) {
            SelectableListSheetHeader(
                title = title,
                showSelectAll = showSelectAll,
                selectAllChecked = selectAllChecked,
                selectAllEnabled = selectAllEnabled,
                selectAllText = selectAllText,
                onToggleSelectAll = onToggleSelectAll,
            )

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(
                    items = items,
                    key = { it.id.toString() }
                ) { item ->
                    SelectableListItem(
                        state = item,
                        onClick = { onToggleItem(item.id) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text(cancelText)
                }
                Button(
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = confirmEnabled,
                    onClick = onConfirm
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}

/**
 * 复选底部弹层标题与可选全选控制。
 *
 * 全选仅作为批量操作入口；是否显示和具体文案由调用方决定，避免组件绑定某个选择语义。
 */
@Composable
private fun SelectableListSheetHeader(
    title: String,
    showSelectAll: Boolean,
    selectAllChecked: Boolean,
    selectAllEnabled: Boolean,
    selectAllText: String?,
    onToggleSelectAll: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        if (showSelectAll && onToggleSelectAll != null && selectAllText != null) {
            Row(
                modifier = Modifier.clickable(enabled = selectAllEnabled, onClick = onToggleSelectAll),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selectAllChecked,
                    enabled = selectAllEnabled,
                    onCheckedChange = { onToggleSelectAll() }
                )
                Text(
                    text = selectAllText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectAllEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }
        }
    }
}

/**
 * 通用复选列表行。
 *
 * 行整体可点击，Checkbox 也触发同一回调；副标题用于包名、说明等辅助识别信息。
 */
@Composable
internal fun <Id> SelectableListItem(
    state: SelectableListItemState<Id>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!state.subtitle.isNullOrBlank()) {
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Checkbox(
            checked = state.selected,
            onCheckedChange = { onClick() }
        )
    }
}

/**
 * 底部选择操作条。
 *
 * 适用于下载记录、回收站等多选管理态；组件只展示数量和主操作，是否可点击与具体动作由调用方控制。
 */
@Composable
internal fun SelectionActionBar(
    selectedText: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    /** 主操作图标颜色跟随按钮可用性；禁用时降级，避免无选中项时仍像可执行删除。 */
    val actionIconTint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        // 作为 Scaffold.bottomBar 使用时不能再占满全高，否则会把主内容区全部挤没。
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    enabled = enabled,
                    onClick = onAction
                ) {
                    DeleteActionIcon(contentDescription = null, tint = actionIconTint, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(actionText)
                }
            }
        }
    }
}

/**
 * 剪贴批量选择底部操作栏。
 *
 * 第一版只承载“删除”和“折叠”两个批量动作；调用方负责决定按钮可用性和执行中的防连点状态。
 */
@Composable
internal fun ClipBatchSelectionActionBar(
    selectedText: String,
    deleteText: String,
    foldText: String,
    onDelete: () -> Unit,
    onFold: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    /** 删除图标颜色跟随可用性；禁用时降级，避免 0 选中时误导用户可以执行危险操作。 */
    val deleteIconTint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        // 作为 Scaffold.bottomBar 使用时只占据底部实际高度，列表额外 bottom padding 由调用方负责。
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    enabled = enabled,
                    onClick = onFold
                ) {
                    Text(foldText)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = enabled,
                    onClick = onDelete
                ) {
                    DeleteActionIcon(contentDescription = null, tint = deleteIconTint, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(deleteText)
                }
            }
        }
    }
}
