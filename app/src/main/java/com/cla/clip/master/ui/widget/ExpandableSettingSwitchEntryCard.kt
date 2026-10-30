package com.cla.clip.master.ui.widget

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.ClipMasterTheme

/** 说明高度与箭头使用同一短动画时长（毫秒），避免展开反馈与卡片布局不同步。 */
private const val DescriptionAnimationDurationMillis = 200

/**
 * 可展开说明的共享开关卡片；卡片只负责阅读，只有右侧开关发送业务操作。
 *
 * 展开状态仅属于当前组合实例，新进入组合默认收起，不持久化也不启动副作用。
 * enabled 只约束开关；加载/保存中仍可阅读说明。supportingContent 用于始终可见的错误或重试，
 * 不适用于需要随说明折叠的正文；调用方负责资源化文案、权限判断和设置提交。
 */
@Composable
internal fun ExpandableSettingSwitchEntryCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable () -> Unit = {},
) {
    /** 每张卡片独立保存本次阅读状态，不随权限状态或说明更新自动折叠。 */
    var expanded by remember { mutableStateOf(false) }
    ExpandableSettingSwitchEntryCardContent(
        title = title,
        description = description,
        checked = checked,
        enabled = enabled,
        icon = icon,
        onCheckedChange = onCheckedChange,
        expanded = expanded,
        // 此回调只修改组合内阅读状态，不能转发业务开关事件。
        onExpandedChange = { expanded = it },
        modifier = modifier,
        supportingContent = supportingContent,
    )
}

/** 纯展示层复用已有卡片骨架；独立接收展开状态，方便预览展开、禁用与长文本边界。 */
@Composable
private fun ExpandableSettingSwitchEntryCardContent(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable () -> Unit = {},
) {
    /** 复用通用展开状态资源，读屏无需依赖箭头朝向理解当前阅读状态。 */
    val expansionDescription = stringResource(if (expanded) R.string.host_dropdown_expanded else R.string.host_dropdown_collapsed)
    /** 箭头随全文可见状态旋转；只改变视觉反馈，不新增第二个可点击控件。 */
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(DescriptionAnimationDurationMillis),
        label = "settingDescriptionArrow",
    )
    SettingSwitchEntryCard(
        title = title,
        checked = checked,
        enabled = enabled,
        icon = icon,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        // 标题、说明和卡片空白区域统一只展开/收起；子 Switch 自己消费操作。
        onEntryClick = { onExpandedChange(!expanded) },
        descriptionContent = {
            // 状态放在点击层内部，由父卡片合并到同一个读屏节点，避免外壳产生孤立的状态焦点。
            Column(
                Modifier
                    .semantics { stateDescription = expansionDescription }
                    .animateContentSize(animationSpec = tween(DescriptionAnimationDurationMillis)),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = description,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(arrowRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 错误和重试属于操作反馈，折叠说明时不能把它们一起隐藏。
                supportingContent()
            }
        },
    )
}

/** 窄屏与大字体下对照折叠、展开及禁用状态，预览只展示资源文案，不执行权限操作。 */
@Preview(name = "折叠与展开", showBackground = true, widthDp = 320)
@Preview(name = "深色大字体", showBackground = true, widthDp = 320, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExpandableSettingSwitchEntryCardPreview() {
    ClipMasterTheme(dynamicColor = false) {
        Column {
            ExpandableSettingSwitchEntryCardContent(
                title = stringResource(R.string.host_shizuku_permission_title),
                description = stringResource(R.string.host_shizuku_service_require),
                checked = false,
                enabled = true,
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                onCheckedChange = { /* 预览不执行真实授权。 */ },
                expanded = false,
                onExpandedChange = { /* 固定折叠样本供视觉对照。 */ },
            )
            ExpandableSettingSwitchEntryCardContent(
                title = stringResource(R.string.host_link_preview_title),
                description = stringResource(R.string.host_link_preview_description),
                checked = false,
                enabled = false,
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                onCheckedChange = { /* 预览不保存设置。 */ },
                expanded = true,
                onExpandedChange = { /* 固定展开样本供视觉对照。 */ },
                supportingContent = {
                    Text(stringResource(R.string.host_privacy_settings_load_failed), color = MaterialTheme.colorScheme.error)
                },
            )
        }
    }
}
