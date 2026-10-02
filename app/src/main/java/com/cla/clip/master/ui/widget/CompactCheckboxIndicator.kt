package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 紧凑复选展示标记；不处理点击或语义，调用方整行负责至少 48dp 热区和 Checkbox 语义。 */
@Composable
internal fun CompactCheckboxIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    /** 选中强调与未选中轮廓复用主题；禁用降低对比度，不伪造业务选择。 */
    val indicatorColor = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Icon(
        imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
        contentDescription = null,
        modifier = modifier.size(18.dp),
        tint = if (enabled) indicatorColor else indicatorColor.copy(alpha = 0.38f),
    )
}
