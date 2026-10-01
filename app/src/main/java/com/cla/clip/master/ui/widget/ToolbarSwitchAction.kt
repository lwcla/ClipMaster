package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.theme.ClipMasterTheme

/**
 * 紧凑标题开关，宽度位于通用标题栏 112dp 保护区内，长标签省略但开关始终可见。
 * 整体提供单一 Switch 点击/无障碍语义，只抛目标状态，不扫描或保存业务数据。
 */
@Composable
internal fun ToolbarSwitchAction(
    label: String,
    accessibilityDescription: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .width(108.dp)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = accessibilityDescription }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 大字体窄标题区预览，标签省略后仍保留开关和完整读屏描述。 */
@Preview(showBackground = true, widthDp = 108, fontScale = 1.5f)
@Composable
private fun ToolbarSwitchActionPreview() {
    ClipMasterTheme {
        ToolbarSwitchAction(
            label = stringResource(com.cla.clip.master.R.string.host_clip_source_filter_system_apps),
            accessibilityDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clip_source_block_show_system_apps),
            checked = true,
            // 预览只检查布局，不修改业务状态。
            onCheckedChange = {},
        )
    }
}
