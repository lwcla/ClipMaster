package com.cla.clip.master.ui.widget

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens

/**
 * 图标、单行标题和右侧当前值组成的下拉设置卡片。
 *
 * 只展示上层已提交值并回传事件，不保存业务数据或持有导航、Repository。
 * 菜单状态由调用方提升管理，离页或进入后台时应关闭；相同值只关闭菜单，不重复提交。
 */
@Composable
internal fun <T> SettingDropdownEntryCard(
    // 调用方资源化的标题；宽度不足时单行省略，完整含义通过读屏描述保留。
    title: String,
    // 右侧紧凑文案，不含“当前”前缀；完整选项文案仍由菜单展示。
    selectedLabel: String,
    // 业务值必须稳定且互不重复，菜单以其作为身份而非列表下标。
    options: List<SingleChoiceOption<T>>,
    // 上层唯一的已提交值，用于菜单勾选和避免重复写入。
    selectedValue: T,
    // 由调用方按页面真实可见性控制，不跨离页或重建恢复。
    expanded: Boolean,
    // 只请求展开或关闭菜单，调用方负责生命周期约束。
    onExpandedChange: (Boolean) -> Unit,
    // 新值选择事件；关闭菜单后触发，持久化及失败反馈由调用方承担。
    onSelect: (T) -> Unit,
    // 完整资源化说明，包含标题、当前值及操作用途，避免短文案丢失语义。
    accessibilityDescription: String,
    // 调用方提供布局约束，保留公共组件可复用性。
    modifier: Modifier = Modifier,
    // 纯展示图标，不额外设置点击或读屏节点。
    icon: @Composable () -> Unit,
) {
    // 沿用入口卡片主题间距，避免为下拉设置建立另一套样式。
    val spacing = ClipMasterThemeTokens.tokens.spacing
    ClipMasterCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small)
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                // 整卡只有一个读屏入口，覆盖内部标题/短值的重复朗读。
                contentDescription = accessibilityDescription
                role = Role.Button
                // 无障碍点击与触摸调用同一展开事件，不产生额外保存动作。
                onClick {
                    onExpandedChange(true)
                    true
                }
            },
        // 整卡接收触摸；右侧锚点只负责布局，不嵌套另一个点击层。
        onClick = { onExpandedChange(true) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Spacer(Modifier.width(spacing.medium))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(spacing.small))
            // 菜单使用当前值区域作为锚点，靠右显示并由 Popup 处理屏幕边缘约束。
            // 当前值区域最多 132dp，为窄屏标题保留空间；短值仍按内容收缩。
            Box(modifier = Modifier.widthIn(max = 132.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    // 菜单外点击或返回只关闭，不提交业务设置。
                    onDismissRequest = { onExpandedChange(false) },
                ) {
                    // 每个选项通过稳定业务值维持身份，避免顺序变化影响勾选。
                    options.forEach { option ->
                        key(option.value) {
                            // 当前项以唯一已提交值判断，失败时仍保留旧项。
                            val isSelected = option.value == selectedValue
                            DropdownMenuItem(
                                text = { Text(option.title) },
                                modifier = Modifier.semantics { selected = isSelected },
                                // 先关闭再回传新值；重复点击当前项不触发写入。
                                onClick = {
                                    onExpandedChange(false)
                                    if (!isSelected) onSelect(option.value)
                                },
                                trailingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 默认、深色和窄屏大字体预览，检查单行布局与当前值不重叠。 */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, widthDp = 320, fontScale = 2f)
@Composable
private fun SettingDropdownEntryCardPreview() {
    SettingDropdownEntryCardSample(initiallyExpanded = false)
}

/** 展开菜单预览，检查五个选项和“无”的完整语义；预览不写入设置。 */
@Preview(showBackground = true, widthDp = 320, heightDp = 420)
@Composable
private fun SettingDropdownEntryCardMenuPreview() {
    SettingDropdownEntryCardSample(initiallyExpanded = true)
}

/** 合成预览数据采用资源 id 作为稳定值，不依赖我的页枚举或真实 DataStore。 */
@Composable
private fun SettingDropdownEntryCardSample(
    // 只决定预览首次展开状态，后续点击仍通过本地 UI 状态控制。
    initiallyExpanded: Boolean,
) {
    ClipMasterTheme {
        // 五项合成选择数据与生产文案一致，用于检查长选项边界。
        val options = listOf(
            SingleChoiceOption(R.string.base_general_copy, stringResource(R.string.base_general_copy)),
            SingleChoiceOption(R.string.base_general_pinned, stringResource(R.string.base_general_pinned)),
            SingleChoiceOption(R.string.base_general_delete, stringResource(R.string.base_general_delete)),
            SingleChoiceOption(R.string.base_general_fold_clip, stringResource(R.string.base_general_fold_clip)),
            SingleChoiceOption(R.string.base_general_no_quick_action, stringResource(R.string.base_general_no_quick_action)),
        )
        // 预览只有菜单瞬时状态，选择回调不保存任何真实设置。
        var expanded by remember { mutableStateOf(initiallyExpanded) }
        SettingDropdownEntryCard(
            title = stringResource(R.string.base_general_clip_item_quick_action_setting),
            selectedLabel = stringResource(R.string.base_general_no_quick_action_short),
            options = options,
            selectedValue = R.string.base_general_no_quick_action,
            expanded = expanded,
            // 预览只切换菜单，不执行导航或业务动作。
            onExpandedChange = { expanded = it },
            onSelect = {},
            accessibilityDescription = stringResource(
                R.string.base_general_clip_item_quick_action_accessibility,
                stringResource(R.string.base_general_no_quick_action),
            ),
            icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        )
    }
}
