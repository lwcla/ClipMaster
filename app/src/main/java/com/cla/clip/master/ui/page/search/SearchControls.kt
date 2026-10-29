package com.cla.clip.master.ui.page.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.search.ClipSearchField
import com.cla.clip.master.ui.widget.SearchTopBar
import com.cla.clip.base.general.dao.data.SourceAppCandidate
import com.cla.clip.master.ui.widget.sourceapp.AppIconLoader
import com.cla.clip.master.ui.widget.sourceapp.EmptyAppIconLoader

/** 页面瞬时互斥菜单状态，不跨重建或离页保存。 */
internal enum class SearchFilterMenu {
    /** 无浮层。 */
    None,
    /** 搜索字段单选浮层。 */
    Target,
    /** 时间单选浮层。 */
    Time,
    /** 来源即时多选浮层。 */
    Source,
}

/**
 * 无标题搜索页顶部搜索头。
 *
 * 该组件只负责展示搜索框和筛选条件，并按页面层传入的运行时偏移整体移动；
 * 折叠状态、滚动优先级和历史模式切换都留在 SearchPage 中统一编排。
 * 筛选按钮底部不额外留白，也不显示分割线或阴影，高度变化继续由页面实测。
 */
@Composable
internal fun SearchCollapsibleHeader(
    /** 页面唯一关键词，展示层不再规整输入。 */
    query: String,
    /** 当前时间与来源筛选，保持页面查询边界。 */
    filterState: SearchFilterState,
    /** 页面映射的来源名称，只用于筛选入口摘要。 */
    selectedSourceAppNames: List<String>,
    /** 生命周期内收集的来源候选，不由搜索头加载。 */
    sourceApps: List<SourceAppCandidate>,
    /** 页面注入图标加载边界，纯预览默认占位。 */
    iconLoader: AppIconLoader = EmptyAppIconLoader,
    /** 页面统一控制的互斥菜单，不在组件内保存。 */
    expandedMenu: SearchFilterMenu,
    /** 滚动驱动的像素位移，真实高度仍由页面测量。 */
    offsetPx: Float,
    /** 搜索头整体布局及尺寸测量接口。 */
    modifier: Modifier = Modifier,
    /** 仅作用于输入节点的焦点、触摸与读屏意图修饰。 */
    searchFieldModifier: Modifier = Modifier,
    /** 真实编辑事件交给页面维护历史及查询。 */
    onQueryChange: (String) -> Unit,
    /** 真实输入焦点变化由页面保护菜单被动恢复。 */
    onFocusChange: (Boolean) -> Unit,
    /** 顶部返回交给页面按局部交互优先级处理。 */
    onBack: () -> Unit,
    /** 清空只发出事件，焦点及筛选保留由页面负责。 */
    onClear: () -> Unit,
    /** IME 搜索交给页面提交并切换结果。 */
    onSubmit: () -> Unit,
    /** 页面负责菜单互斥、广告保护与生命周期关闭。 */
    onMenuChange: (SearchFilterMenu) -> Unit,
    /** 搜索字段选择由页面更新唯一条件。 */
    onToggleField: (ClipSearchField) -> Unit,
    /** 时间选中后沿用页面即时查询。 */
    onTimeSelected: (SearchTimeFilter) -> Unit,
    /** 来源全选事件只交给既有选择规则。 */
    onToggleAllSources: () -> Unit,
    /** 稳定包名标识具体来源，包括未知来源空包名。 */
    onToggleSource: (String) -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                /** 搜索头运行时位移由页面滚动状态驱动，单位为像素，避免 Dp 换算造成跟手误差。 */
                translationY = offsetPx
            },
        color = MaterialTheme.colorScheme.surface,
        // 搜索栏自身使用原始 surface；外层也禁用色调叠加，避免筛选区出现另一块底色。
        tonalElevation = 0.dp,
        // 筛选按钮下方不再显示投影形成的横线，滚动时也保持同一底部样式。
        shadowElevation = 0.dp,
    ) {
        Column(
            // SearchTopBar 唯一处理状态栏；筛选按钮底部不加空白或分隔效果。
            modifier = Modifier.fillMaxWidth()
        ) {
            SearchBar(
                query = query,
                inputModifier = searchFieldModifier,
                onQueryChange = onQueryChange,
                onFocusChange = onFocusChange,
                onBack = onBack,
                onClear = onClear,
                onSubmit = onSubmit,
            )

            SearchFilters(
                filterState = filterState,
                selectedSourceAppNames = selectedSourceAppNames,
                sourceApps = sourceApps,
                iconLoader = iconLoader,
                expandedMenu = expandedMenu,
                onMenuChange = onMenuChange,
                onToggleField = onToggleField,
                onTimeSelected = onTimeSelected,
                onToggleAllSources = onToggleAllSources,
                onToggleSource = onToggleSource,
            )
        }
    }
}

/**
 * 搜索输入框。
 *
 * 复用来源过滤页无边框搜索栏；输入规整在 ViewModel 层完成，焦点和业务事件只向页面回传。
 */
@Composable
internal fun SearchBar(
    /** 当前页面关键词，不由组件保存副本。 */
    query: String,
    /** 搜索行的整体布局接口，不承载输入焦点。 */
    modifier: Modifier = Modifier,
    /** 实际输入节点的焦点请求及用户意图监听。 */
    inputModifier: Modifier = Modifier,
    /** 关键词编辑交给页面更新唯一状态。 */
    onQueryChange: (String) -> Unit,
    /** 输入真实焦点变化，避免返回按钮获得焦点触发历史。 */
    onFocusChange: (Boolean) -> Unit,
    /** 页面负责返回优先级和宿主导航。 */
    onBack: () -> Unit,
    /** 页面负责清词、恢复编辑焦点及展示历史。 */
    onClear: () -> Unit,
    /** 页面负责提交查询并切换结果模式。 */
    onSubmit: () -> Unit,
) {
    SearchTopBar(
        query = query,
        onQueryChange = onQueryChange,
        onBack = onBack,
        onClear = onClear,
        onSubmit = onSubmit,
        placeholder = stringResource(com.cla.clip.base.general.R.string.base_general_search_clip_hint),
        backContentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_back),
        clearContentDescription = stringResource(com.cla.clip.base.general.R.string.base_general_clear_search_keyword),
        modifier = modifier,
        // 监听与 FocusRequester 都挂实际输入节点，菜单关闭后的焦点继续由页面 guard 判断。
        inputModifier = inputModifier.onFocusChanged { onFocusChange(it.isFocused) },
    )
}
