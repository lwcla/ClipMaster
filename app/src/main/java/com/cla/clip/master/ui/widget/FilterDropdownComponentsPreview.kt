package com.cla.clip.master.ui.widget

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.ClipMasterTheme

/** 检查无标题入口、窄屏、大字体、暗色和长值边界；预览不执行查询。 */
@Preview(showBackground = true, widthDp = 320)
@Preview(showBackground = true, widthDp = 320, fontScale = 1.8f)
@Preview(showBackground = true, widthDp = 320, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FilterDropdownEntryPreview() {
    ClipMasterTheme {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterDropdown(stringResource(com.cla.clip.base.general.R.string.base_general_all_time),
                stringResource(com.cla.clip.base.general.R.string.base_general_time), false, {}, Modifier.weight(2f)) {}
            FilterDropdown(stringResource(R.string.host_preview_app_title),
                stringResource(com.cla.clip.base.general.R.string.base_general_source_app), false, {}, Modifier.weight(3f)) {}
        }
    }
}

/** 单选内容预览检查当前值勾选；生产入口负责菜单焦点与定位。 */
@Preview(showBackground = true, widthDp = 180)
@Composable
private fun SingleChoiceDropdownPreview() {
    ClipMasterTheme {
        Surface {
            SingleChoiceDropdownContent(
                options = listOf(
                    SingleChoiceOption(0, stringResource(com.cla.clip.base.general.R.string.base_general_all_time)),
                    SingleChoiceOption(1, stringResource(com.cla.clip.base.general.R.string.base_general_today)),
                ), selectedValue = 0, onSelect = {}, onDismiss = {},
            )
        }
    }
}

/** 窄菜单长名称预览验证紧凑单行、图标 slot、左侧勾选与大字体增长，不读取安装应用。 */
@Preview(showBackground = true, widthDp = 180, heightDp = 320)
@Preview(showBackground = true, widthDp = 180, heightDp = 320, fontScale = 1.8f)
@Preview(showBackground = true, widthDp = 180, heightDp = 320, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SelectableDropdownLongListPreview() {
    ClipMasterTheme {
        /** 合成长标题由已有资源生成，身份使用固定测试前缀，避免引用真实来源。 */
        val title = stringResource(R.string.host_preview_app_title)
        /** 默认图标用于检查缺失图标边界，预览不访问本地缓存或真实来源。 */
        val defaultIcon = painterResource(R.drawable.host_ic_app_icon_default)
        Surface {
            Column(Modifier.height(320.dp)) {
                SelectableDropdownContent(
                    // 身份只供稳定 key 使用，不作为副标题展示；合成名称检查单行省略。
                    items = List(30) { SelectableListItemState(id = "fixture.$it", title = title.repeat(2), selected = false) },
                    emptyText = stringResource(R.string.host_search_source_empty),
                    onToggleItem = {},
                    headerContent = { DropdownSelectAllHeader(false, true,
                        stringResource(com.cla.clip.base.general.R.string.base_general_select_all), {}) },
                    // 纯展示 slot 与真实来源保持 24dp 尺寸，图标不重复朗读应用名称。
                    itemLeadingContent = { Icon(defaultIcon, contentDescription = null, modifier = Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.Unspecified) },
                )
            }
        }
    }
}

/** 无候选空态预览，不能把暂无候选当作取消已应用的筛选。 */
@Preview(showBackground = true, widthDp = 180)
@Composable
private fun SelectableDropdownEmptyPreview() {
    ClipMasterTheme {
        Surface {
            SelectableDropdownContent(emptyList(), stringResource(R.string.host_search_source_empty), {},
                headerContent = { DropdownSelectAllHeader(false, false,
                    stringResource(com.cla.clip.base.general.R.string.base_general_select_all), {}) })
        }
    }
}
