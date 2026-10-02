package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cla.clip.base.general.dao.SourceAppData
import com.cla.clip.base.general.utils.displayName
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.SelectableDropdownContent
import com.cla.clip.master.ui.widget.SelectableListItemState
import com.cla.clip.master.ui.widget.sourceapp.rememberSourceAppIcon

/** 来源下拉薄适配只展示图标与名称；复用缓存请求与共享紧凑选择行，不保存草稿或写入查询。 */
@Composable
internal fun SourceAppDropdownContent(
    /** 页面生命周期内收集的来源候选，图标来自现有缓存，不扫描安装应用。 */
    sourceApps: List<SourceAppData>,
    /** 页面已应用的即时勾选态，组件不保存副本。 */
    selection: SourceAppSelection,
    /** 全选事件沿用既有来源选择规则。 */
    onToggleAll: () -> Unit,
    /** 单项事件原样传回包名，未知来源的空包名仍是合法身份。 */
    onToggleItem: (String) -> Unit,
) {
    /** 候选变化才重建图标索引；可见行按稳定身份查找，避免每行重复遍历候选。 */
    val sourceAppsByPackage = remember(sourceApps) { sourceApps.associateBy { it.packageName } }
    /** 缓存不存在、不可读或解码失败时使用项目既有默认应用图标。 */
    val defaultIcon = painterResource(R.drawable.host_ic_app_icon_default)
    SelectableDropdownContent(
        items = sourceApps.map { sourceApp ->
            // 来源展示复用共享兜底；身份保留空包名，不依赖列表下标。
            SelectableListItemState(
                id = sourceApp.packageName,
                title = sourceApp.displayName(),
                selected = selection.contains(sourceApp.packageName),
            )
        },
        allSelected = selection.isAllSelected,
        selectAllContentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_select_all),
        emptyText = stringResource(R.string.host_search_source_empty),
        onToggleAll = onToggleAll,
        onToggleItem = onToggleItem,
        // 懒列表只为已组合的行构建现有 Coil 请求，菜单关闭后不保留独立加载任务。
        itemLeadingContent = { item ->
            /** 当前稳定身份对应的缓存元数据，不使用显示名称匹配来源。 */
            val sourceApp = sourceAppsByPackage[item.id]
            /** 可见行异步读取缓存，缓存解码失败时按包名取系统图标；离开组合自动取消。 */
            val iconModel = rememberSourceAppIcon(item.id, sourceApp?.iconPath, sourceApp?.iconHash)
            AsyncImage(
                model = iconModel,
                contentDescription = null,
                placeholder = defaultIcon,
                fallback = defaultIcon,
                error = defaultIcon,
                modifier = Modifier.size(24.dp),
                contentScale = ContentScale.Fit,
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
