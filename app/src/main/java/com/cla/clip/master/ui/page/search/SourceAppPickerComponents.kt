package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.widget.sourceapp.AppIcon
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.master.ui.widget.sourceapp.AppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.EmptyAppIconLoader
import com.cla.clip.base.general.utils.toSourceAppDisplayName
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.DropdownSelectAllHeader
import com.cla.clip.master.ui.widget.SelectableDropdownContent
import com.cla.clip.master.ui.widget.SelectableListItemState

/** 来源下拉先展示名称，图标按可见包名加载；复用共享选择行，不扫描或持久化候选。 */
@Composable
internal fun SourceAppDropdownContent(
    /** 页面生命周期内收集的轻量来源投影，不在菜单内扫描安装应用。 */
    sourceApps: List<SourceAppCandidate>,
    /** 页面加载边界可替换，菜单只加载懒布局当前组合项。 */
    iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 页面已应用的即时勾选态，组件不保存副本。 */
    selection: SourceAppSelection,
    /** 全选事件沿用既有来源选择规则。 */
    onToggleAll: () -> Unit,
    /** 单项事件原样传回包名，未知来源的空包名仍是合法身份。 */
    onToggleItem: (String) -> Unit,
) {
    SelectableDropdownContent(
        items = sourceApps.map { sourceApp ->
            // 来源展示复用共享兜底；身份保留空包名，不依赖列表下标。
            SelectableListItemState(
                id = sourceApp.packageName,
                title = sourceApp.appName.toSourceAppDisplayName(),
                selected = selection.contains(sourceApp.packageName),
            )
        },
        emptyText = stringResource(R.string.host_search_source_empty),
        headerContent = {
            DropdownSelectAllHeader(selection.isAllSelected, sourceApps.isNotEmpty(),
                stringResource(com.cla.clip.base.general.R.string.base_general_select_all), onToggleAll)
        },
        onToggleItem = onToggleItem,
        // 菜单行进入组合且处于 STARTED 才加载；关闭菜单后释放图像引用。
        itemLeadingContent = { item ->
            AppIcon(
                packageName = item.id,
                loader = iconLoader,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

/** 来源摘要名映射；缺失候选保留包名兜底，查询无来源限制时返回空列表以显示全部来源。 */
internal fun Map<String, String>.toSelectedSourceAppNames(selectedPackageNames: Set<String>): List<String> {
    if (selectedPackageNames.isEmpty()) return emptyList()
    /** 仍存在候选的名称，调用方已经统一处理未知名称。 */
    val appNames = filterKeys { packageName -> packageName in selectedPackageNames }.values.toList()
    /** 已消失的旧筛选包名不丢弃，排序保证摘要稳定。 */
    val missingPackageNames = selectedPackageNames.filterNot { packageName -> containsKey(packageName) }.sorted()
    return appNames + missingPackageNames
}
