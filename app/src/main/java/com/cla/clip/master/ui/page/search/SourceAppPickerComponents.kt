package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.base.general.utils.toSourceAppDisplayName
import com.cla.clip.master.R
import com.cla.clip.master.ui.widget.SelectableDropdownContent
import com.cla.clip.master.ui.widget.SelectableListItemState
import com.cla.clip.master.ui.widget.clip.rememberSourceAppIconModel
import com.cla.clip.master.ui.widget.sourceapp.SourceAppPresentationProvider
import com.cla.clip.master.ui.widget.sourceapp.SourceAppPresentationState
import com.cla.clip.master.ui.widget.sourceapp.EmptySourceAppPresentationProvider

/** 来源下拉薄适配只展示图标与名称；复用缓存请求与共享紧凑选择行，不保存草稿或写入查询。 */
@Composable
internal fun SourceAppDropdownContent(
    /** 页面生命周期内收集的来源候选，图标来自现有缓存，不扫描安装应用。 */
    sourceApps: List<SourceAppCandidate>,
    /** 页面已应用的即时勾选态，组件不保存副本。 */
    selection: SourceAppSelection,
    /** 全选事件沿用既有来源选择规则。 */
    onToggleAll: () -> Unit,
    /** 单项事件原样传回包名，未知来源的空包名仍是合法身份。 */
    onToggleItem: (String) -> Unit,
    /** 图标来自单包名状态，候选只携带包名和名称。 */
    sourceAppPresentation: SourceAppPresentationProvider = EmptySourceAppPresentationProvider,
) {
    /** 缓存不存在、不可读或解码失败时使用项目既有默认应用图标。 */
    val defaultIcon = painterResource(R.drawable.host_ic_app_icon_default)
    SelectableDropdownContent(
        items = sourceApps.map { sourceApp ->
            // 来源展示复用共享兜底；身份保留空包名，不依赖列表下标。
            SelectableListItemState(
                id = sourceApp.packageName,
                title = sourceApp.appName.toSourceAppDisplayName(),
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
            /** 独立展示流不改变菜单候选和选择身份。 */
            val sourceState = sourceAppPresentation.presentation(item.id)
            /** 名称/图标元数据与候选身份分离，缺失时使用默认资源。 */
            val sourceApp = (sourceState as? SourceAppPresentationState.Ready)?.source
            /** 系统读取由协调器去重，UI 只加载完成的版本文件。 */
            val iconModel = rememberSourceAppIconModel(sourceApp?.iconPath, sourceApp?.iconHash)
            AsyncImage(
                model = iconModel,
                contentDescription = null,
                placeholder = defaultIcon,
                fallback = defaultIcon,
                error = defaultIcon,
                onError = { sourceAppPresentation.reportDecodeFailure(item.id) },
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
