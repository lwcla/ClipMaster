package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.cla.clip.master.R
import kotlin.math.roundToInt

/**
 * 无标题的通用筛选下拉入口。业务勾选与展开状态由调用方持有，内部只测量锚点。
 * Popup 使用菜单焦点与外部关闭行为，固定宽度和窗口最大高度支持懒列表，避免 intrinsic 测量冲突。
 */
@Composable
internal fun FilterDropdown(
    /** 当前选择摘要，长文本在入口内单行省略。 */
    value: String,
    /** 调用方资源化的维度与完整选择摘要，保留读屏信息。 */
    accessibilityDescription: String,
    /** 外部统一管理的瞬时菜单状态，组件只保存几何信息。 */
    expanded: Boolean,
    /** 展开与关闭仅回传意图，不直接更改业务筛选。 */
    onExpandedChange: (Boolean) -> Unit,
    /** 所属页面分配入口宽度，并在此测量真实窗口锚点。 */
    modifier: Modifier = Modifier,
    /** 可选最小菜单宽度，与共享规则默认值一致并受窗口安全区限制。 */
    menuMinWidth: Dp = DEFAULT_FILTER_DROPDOWN_MENU_WIDTH,
    /** 纯展示菜单内容，业务数据与勾选事件由调用方提供。 */
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    /** 最新窗口尺寸用于多窗口/横屏；安全区来自当前窗口 Insets，不用整屏配置估算。 */
    val windowSize = LocalWindowInfo.current.containerSize
    /** 当前密度负责像素定位与菜单 Dp 约束之间的转换。 */
    val density = LocalDensity.current
    /** 当前锚点真实窗口坐标，入口被搜索头移动后会重新测量。 */
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    /** 浮层四边额外留白，单位像素，布局约束和定位使用相同值。 */
    val marginPx = with(density) { 8.dp.roundToPx() }
    /** 浮层与锚点间隔，单位像素，避免视觉贴合。 */
    val gapPx = with(density) { 4.dp.roundToPx() }
    /** 逻辑方向用于正确解析横屏安全区，定位协作者也保留 RTL 对齐。 */
    val layoutDirection = LocalLayoutDirection.current
    /** 顶部状态栏与刘海等安全绘制区，单位像素。 */
    val topInsetPx = WindowInsets.safeDrawing.getTop(density)
    /** 底部安全绘制区含输入法和导航栏，单位像素，只在完整窗口扣除一次。 */
    val bottomInsetPx = WindowInsets.safeDrawing.getBottom(density)
    /** 左侧系统安全区用于横屏导航栏和侧边刘海避让，单位像素。 */
    val leftInsetPx = WindowInsets.safeDrawing.getLeft(density, layoutDirection)
    /** 右侧系统安全区独立扣除，防止菜单伸入横屏导航栏，单位像素。 */
    val rightInsetPx = WindowInsets.safeDrawing.getRight(density, layoutDirection)
    /** 定位协作者只随窗口安全区变化重建，锚点由 Popup 每次布局传入。 */
    val positionProvider = remember(marginPx, gapPx, topInsetPx, bottomInsetPx, windowSize, leftInsetPx, rightInsetPx) {
        FilterDropdownPositionProvider(marginPx, topInsetPx, bottomInsetPx, gapPx, windowSize, leftInsetPx, rightInsetPx)
    }
    /** 可见菜单宽度至少覆盖入口；来源可请求更宽布局，始终受当前窗口边缘限制。 */
    val menuWidth = with(density) {
        resolveFilterDropdownMenuWidth(anchor.width.toDp(),
            (windowSize.width - leftInsetPx - rightInsetPx - marginPx * 2).coerceAtLeast(1).toDp(), menuMinWidth)
    }
    /** 最大高度使用锚点上下实际空间；内容超出时交给来源懒列表滚动。 */
    val maxHeight = with(density) { positionProvider.availableHeight(anchor, windowSize).toDp() }
    /** 读屏状态独立于可见当前值，保留筛选维度与展开语义。 */
    val expandedDescription = stringResource(if (expanded) R.string.host_dropdown_expanded else R.string.host_dropdown_collapsed)
    Box(modifier = modifier.onGloballyPositioned { coordinates ->
        // 只保存几何信息，不触发查询或改变页面模式。
        val bounds = coordinates.boundsInWindow()
        anchor = IntRect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    }) {
        Surface(
            // 点击整个入口切换浮层，业务选择由调用方独立持有。
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics {
                // 整个入口是唯一读屏节点，箭头和可见值不重复朗读。
                contentDescription = accessibilityDescription
                stateDescription = expandedDescription
                role = Role.Button
                // 读屏点击与普通点击使用同一展开回调。
                onClick { onExpandedChange(!expanded); true }
            },
            shape = MaterialTheme.shapes.small,
            // 与共享搜索栏使用相同背景，不额外绘制一块 surfaceVariant 底色。
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                // 返回和外部点击只通知关闭，已应用业务选择由上层保留。
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Column(Modifier.width(menuWidth).heightIn(max = maxHeight).padding(vertical = 8.dp), content = menuContent)
                }
            }
        }
    }
}

/** 通用即时单选菜单内容；紧凑左侧勾选与文字在同一槽内，避免标准图标槽额外占宽。 */
@Composable
internal fun <T> SingleChoiceDropdownContent(
    options: List<SingleChoiceOption<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        options.forEach { option ->
            key(option.value) {
                /** 当前业务值是唯一选中依据，避免列表顺序变化串选。 */
                val isSelected = option.value == selectedValue
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 预留 18dp 标记列使所有选项文字对齐，不使用带最小宽度的 leadingIcon 槽。
                            Box(Modifier.size(18.dp)) {
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(option.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    modifier = Modifier.semantics { selected = isSelected },
                    // 先关闭再提交新值，相同选项不触发业务状态变化。
                    onClick = { onDismiss(); if (!isSelected) onSelect(option.value) },
                )
            }
        }
    }
}

/** 通用即时多选菜单；头部由 slot 可选提供，选择规则和图标差异由调用方表达。 */
@Composable
internal fun SelectableDropdownContent(
    /** 调用方映射的稳定选择项，不由菜单加载业务数据。 */
    items: List<SelectableListItemState<String>>,
    /** 调用方资源化的空态文案，不展示来源名单。 */
    emptyText: String,
    /** 单项选择回传稳定身份，浮层继续保持打开。 */
    onToggleItem: (String) -> Unit,
    /** 菜单内容布局接口，最大高度由外层浮层约束。 */
    modifier: Modifier = Modifier,
    /** 可选图标展示由调用方按项提供，不让共享菜单依赖具体领域实体。 */
    itemLeadingContent: (@Composable (SelectableListItemState<String>) -> Unit)? = null,
    /** 可选独立头部；未提供时不保留全选行或分隔线。 */
    headerContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        headerContent?.invoke()
        if (items.isEmpty()) {
            Text(emptyText, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(items, key = { it.id }) { item ->
                    // 复用选择行与稳定身份，slot 只展示图标，整行保持唯一勾选回调。
                    SelectableListItem(
                        state = item,
                        onClick = { onToggleItem(item.id) },
                        checkboxPlacement = CheckboxPlacement.Leading,
                        leadingContent = itemLeadingContent?.let { content -> { content(item) } },
                    )
                }
            }
        }
    }
}

/** 多选菜单可选全选头部；状态与动作均由调用方提供，保持唯一复选语义。 */
@Composable
internal fun DropdownSelectAllHeader(
    /** 当前全选态。 */ checked: Boolean,
    /** 候选为空时不可切换。 */ enabled: Boolean,
    /** 资源化读屏名称，无可见文字。 */ contentDescription: String,
    /** 整行只回传一次全选事件。 */ onToggle: () -> Unit,
    /** 外部布局接口。 */ modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox,
            onValueChange = { onToggle() })
            .semantics { this.contentDescription = contentDescription }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactCheckboxIndicator(checked = checked, enabled = enabled)
    }
    HorizontalDivider()
}
