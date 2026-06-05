package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 开关设置行 UI state。
 *
 * 调用方负责把权限、偏好设置或业务状态转换成该模型；共享组件只展示说明和开关，不直接执行权限或设置跳转。
 */
internal data class SettingSwitchRowState<Id>(
    /** 调用方用于识别设置项的稳定 id；组件不解析其业务含义。 */
    val id: Id,

    /** 设置项说明，必须由调用方资源化后传入。 */
    val description: String,

    /** 当前开关是否处于开启态。 */
    val checked: Boolean,

    /** 当前设置是否允许交互；禁用时保留展示但不触发回调。 */
    val enabled: Boolean = true,
)

/**
 * 固定设置开关列表卡片。
 *
 * 适用于少量常驻设置项；组件只统一卡片外壳、分隔线和开关行，是否执行授权或保存由调用方回调决定。
 */
@Composable
internal fun <Id> SettingSwitchListCard(
    items: List<SettingSwitchRowState<Id>>,
    onItemCheckedChange: (id: Id, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        // 空列表不绘制设置卡片，避免调用方临时无权限项时留下无意义空白外壳。
        return
    }

    ClipMasterGestureCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentPadding = ClipMasterCardDefaults.ZeroContentPadding,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                SettingSwitchRow(
                    state = item,
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

/**
 * 通用开关设置行。
 *
 * 行内只展示说明和开关，调用方通过 `onCheckedChange` 决定具体授权、偏好保存或系统设置跳转行为。
 */
@Composable
internal fun <Id> SettingSwitchRow(
    state: SettingSwitchRowState<Id>,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = state.checked,
            onCheckedChange = onCheckedChange,
            enabled = state.enabled,
        )
    }
}
