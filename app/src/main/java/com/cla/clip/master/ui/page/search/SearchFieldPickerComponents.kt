package com.cla.clip.master.ui.page.search

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cla.clip.base.general.search.ClipSearchField
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.ClipMasterTheme
import com.cla.clip.master.ui.widget.SelectableDropdownContent
import com.cla.clip.master.ui.widget.SelectableListItemState

/** 字段菜单的页面适配，复用共享多选行且不提供全选头部，不持有第二份状态。 */
@Composable
internal fun SearchFieldsDropdownContent(
    /** 已生效字段集合。 */ fields: Set<ClipSearchField>,
    /** 即时切换，不附带关闭菜单动作。 */ onToggle: (ClipSearchField) -> Unit,
    /** 调用方控制尺寸与布局。 */ modifier: Modifier = Modifier,
) {
    /** 菜单固定七项，显式 id 只用于 UI 身份，不依赖枚举名或索引。 */
    val items = ClipSearchField.entries.map { field ->
        /** 最后一项不能取消；选择新的字段后原项自动恢复可操作。 */
        val canToggle = SearchFieldSelectionReducer.canToggle(fields, field)
        SelectableListItemState(id = field.id, title = field.labelText(), selected = field in fields,
            enabled = canToggle, subtitle = if (canToggle) null else stringResource(R.string.host_search_target_required))
    }
    SelectableDropdownContent(items = items, emptyText = "", modifier = modifier,
        onToggleItem = { id -> ClipSearchField.entries.firstOrNull { it.id == id }?.let(onToggle) })
}

/** 摘要只展示全选、数量或唯一字段，完整选择通过入口的读屏描述提供。 */
@Composable
internal fun Set<ClipSearchField>.summaryText(): String = when (size) {
    ClipSearchField.entries.size -> stringResource(R.string.host_search_target_all)
    1 -> first().labelText()
    else -> stringResource(R.string.host_search_target_count, size)
}

/** 底层字段不依赖 Android，中文标签在搜索 feature 映射。 */
@Composable
internal fun ClipSearchField.labelText(): String = stringResource(when (this) {
    ClipSearchField.Content -> R.string.host_search_target_content
    ClipSearchField.Link -> R.string.host_search_target_link
    ClipSearchField.LinkTitle -> R.string.host_search_target_title
    ClipSearchField.LinkDescription -> R.string.host_search_target_description
    ClipSearchField.SiteName -> R.string.host_search_target_site
    ClipSearchField.SourceName -> R.string.host_search_target_source
    ClipSearchField.SourcePackage -> R.string.host_search_target_package
})

/** 七项菜单的亮暗、大字预览，不查询来源、不保存状态。 */
@Preview(showBackground = true, widthDp = 260, heightDp = 420)
@Preview(showBackground = true, widthDp = 260, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(showBackground = true, widthDp = 260, heightDp = 420, fontScale = 1.8f)
@Composable
private fun SearchFieldsMenuPreview() {
    ClipMasterTheme { Surface { SearchFieldsDropdownContent(ClipSearchField.entries.toSet(), {}) } }
}

/** 最后一项禁用取消时仍显示选择约束，其他六项保持可操作。 */
@Preview(showBackground = true, widthDp = 260, heightDp = 420)
@Composable
private fun SearchFieldsLastSelectionPreview() {
    ClipMasterTheme { Surface { SearchFieldsDropdownContent(setOf(ClipSearchField.Content), {}) } }
}
