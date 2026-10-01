package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens

/**
 * 独立布尔筛选胶囊，两态仅改变边框和填充色，不添加图标或占位。
 * 纯文字和等宽描边保持两态尺寸一致；Material3 保留最小 48dp 热区和选中语义。
 * 文案与状态由调用方传入，组件只回传目标状态，不持有业务状态或执行扫描、保存。
 */
@Composable
internal fun ToggleFilterChip(
    // 调用方资源化短标签，空间不足时省略但不影响完整读屏说明。
    label: String,
    // 完整操作含义，选中状态由 FilterChip 自带语义提供。
    accessibilityDescription: String,
    // 调用方唯一状态来源，组件不建立第二份本地选择状态。
    selected: Boolean,
    // 只发送点击后的目标状态，业务副作用由调用方负责。
    onSelectedChange: (Boolean) -> Unit,
    // 外部控制布局约束，不缩放胶囊或缩小实际点击区域。
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        // 保持原开关的目标状态回调契约，不在组件内触发扫描。
        onClick = { onSelectedChange(!selected) },
        modifier = modifier.semantics { contentDescription = accessibilityDescription },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        shape = MaterialTheme.shapes.small,
        // 两态文字颜色一致，仅以主题填充和描边颜色表达选中状态。
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        // 选中态也保留 1dp 描边，颜色变化不引起几何尺寸变化。
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

/** 两态与深色预览，检查纯文字居中且仅底色、描边颜色发生变化。 */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToggleFilterChipStatesPreview() {
    ClipMasterTheme {
        Column {
            ToggleFilterChip(
                label = stringResource(com.cla.clip.master.R.string.host_clip_source_filter_system_apps),
                accessibilityDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clip_source_block_show_system_apps),
                selected = false,
                // 静态预览只检查外观，不修改业务设置。
                onSelectedChange = {},
            )
            ToggleFilterChip(
                label = stringResource(com.cla.clip.master.R.string.host_clip_source_filter_system_apps),
                accessibilityDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clip_source_block_show_system_apps),
                selected = true,
                // 静态预览只检查外观，不修改业务设置。
                onSelectedChange = {},
            )
        }
    }
}

/** 窄屏大字体数量行预览，摘要占剩余空间并换行，胶囊保留完整热区。 */
@Preview(showBackground = true, widthDp = 320, fontScale = 1.5f)
@Composable
private fun ToggleFilterChipSummaryPreview() {
    ClipMasterTheme {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ClipMasterThemeTokens.tokens.spacing.small),
        ) {
            Text(
                text = stringResource(com.cla.clip.base.general.R.string.base_general_clip_source_block_local_load_summary, 2, 167),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleFilterChip(
                label = stringResource(com.cla.clip.master.R.string.host_clip_source_filter_system_apps),
                accessibilityDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clip_source_block_show_system_apps),
                selected = true,
                // 合成数量预览不执行筛选、扫描或保存。
                onSelectedChange = {},
            )
        }
    }
}
