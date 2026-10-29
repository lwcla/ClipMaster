package com.cla.clip.master.ui.page.search

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.ClipMasterTheme

/** 搜索页专属搜索头集成预览，检查空输入和筛选行在亮暗色主题中仍完整可见。 */
@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchHeaderEmptyPreview() {
    ClipMasterTheme { SearchHeaderPreviewContent(query = "") }
}

/** 有输入与窄屏大字体检查返回/清空热区、统一背景及固定同行的时间、搜索范围和来源入口。 */
@Preview(showBackground = true, widthDp = 480)
@Preview(showBackground = true, widthDp = 280, fontScale = 1.8f)
@Composable
private fun SearchHeaderQueryPreview() {
    ClipMasterTheme {
        SearchHeaderPreviewContent(query = stringResource(R.string.host_preview_app_package).repeat(2))
    }
}

/** 合成状态仅用于布局检查，不加载来源、保存历史或执行导航，避免预览依赖页面副作用。 */
@Composable
private fun SearchHeaderPreviewContent(
    /** 空字符串或资源化合成关键词，不使用真实剪贴及搜索历史。 */
    query: String,
) {
    SearchCollapsibleHeader(
        query = query,
        filterState = SearchFilterState(query = query),
        selectedSourceAppNames = emptyList(),
        sourceApps = emptyList(),
        expandedMenu = SearchFilterMenu.None,
        offsetPx = 0f,
        // 预览回调仅占位，不启动焦点请求、输入法或业务任务。
        onQueryChange = {}, onFocusChange = {}, onBack = {}, onClear = {}, onSubmit = {},
        onMenuChange = {}, onToggleField = {}, onTimeSelected = {}, onToggleAllSources = {}, onToggleSource = {},
    )
}
