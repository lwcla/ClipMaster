package com.cla.clip.master.ui.widget

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 开关设置行 UI state。
 *
 * 调用方负责把权限、偏好设置或业务状态转换成该模型；共享组件只展示标题、说明和开关，不直接执行权限或设置跳转。
 */
internal data class SettingSwitchRowState<Id>(
    /** 调用方用于识别设置项的稳定 id；组件不解析其业务含义。 */
    val id: Id,

    /** 设置项标题，必须由调用方资源化后传入。 */
    val title: String,

    /** 设置项说明，必须由调用方资源化后传入。 */
    val description: String,

    /** 当前开关是否处于开启态。 */
    val checked: Boolean,

    /** 当前设置是否允许交互；禁用时保留展示但不触发回调。 */
    val enabled: Boolean = true,
)

/**
 * 通用可展开设置卡片。
 *
 * 适用于一组同类设置项；展开状态由调用方持有，组件内部只管理箭头旋转和展开动画，避免共享组件理解具体设置来源。
 */
@Composable
internal fun <Id> ExpandableSettingCard(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    items: List<SettingSwitchRowState<Id>>,
    onItemCheckedChange: (id: Id, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ClipMasterGestureCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentPadding = ClipMasterCardDefaults.ZeroContentPadding,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ExpandableSettingHeader(
                title = title,
                expanded = expanded,
                hasItems = items.isNotEmpty(),
                onToggleExpanded = onToggleExpanded,
            )

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
        }
    }
}

/**
 * 可展开设置卡片标题行。
 *
 * 标题行整体可点击，比只点击箭头更符合移动端设置项习惯；箭头旋转只表达展开状态，不改变布局尺寸。
 */
@Composable
private fun ExpandableSettingHeader(
    title: String,
    expanded: Boolean,
    hasItems: Boolean,
    onToggleExpanded: () -> Unit,
) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val rotation by animateFloatAsState(
            targetValue = if (expanded && hasItems) 180f else 0f,
            animationSpec = tween(durationMillis = 260),
            label = "expand_icon_rotation"
        )

        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.rotate(rotation)
        )
    }
}

/**
 * 通用开关设置行。
 *
 * 行内只展示标题、说明和开关，调用方通过 `onCheckedChange` 决定具体授权、偏好保存或系统设置跳转行为。
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
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
