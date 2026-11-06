package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R as BaseR

/** 标题栏内容区最小高度；实际高度允许随动态字体和两行标题增长，不压缩 48dp 按钮热区。 */
private val TitleBarMinContentHeight = 48.dp

/** 标题栏左右内边距，与 48dp 图标按钮配合，保证点击热区贴近系统常见导航栏尺寸。 */
private val TitleBarHorizontalPadding = 4.dp

/** 标题与实际导航/动作区域之间的最小留白，不再为不存在的按钮预留固定宽度。 */
private val TitleBarTitleActionGap = 8.dp

/**
 * 通用返回标题栏。
 *
 * 左侧固定返回按钮，标题最多两行，右侧支持填充操作按钮；适用于大多数二级页面，不负责处理导航栈细节。
 * 标题始终相对整条标题栏居中；操作拥挤时改占独立第二行，避免省略页面身份或与按钮重叠。
 */
@Composable
fun TitleBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TitleBar(
        modifier = modifier,
        navigation = {
            TitleBarBackButton(onBack = onBack)
        },
        title = {
            TitleBarText(text = title)
        },
        actions = {
            actions()
        }
    )
}

/**
 * 插槽式通用标题栏容器。
 *
 * 页面可以按需填充导航区、标题区和动作区；容器统一处理状态栏 padding、48dp 最小内容高度和逻辑起止方向。
 * 先按两侧实际宽度保护居中区域，标题固有宽度放不下时独占下一行；测量结果只参与本轮布局，不保存全局或组合状态。
 */
@Composable
fun TitleBar(
    modifier: Modifier = Modifier,
    navigation: @Composable RowScope.() -> Unit,
    title: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = TitleBarHorizontalPadding),
        content = {
            // 三个独立 Row 保留既有 RowScope 插槽及其中的按钮、间距和权重语义。
            Row(verticalAlignment = Alignment.CenterVertically, content = navigation)
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = title,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        },
    ) { measurables, constraints ->
        // measurables 顺序固定为导航、标题、动作；constraints 已扣除外层状态栏与水平内边距。
        // 松约束允许两侧只占真实内容宽度，不把无按钮的空插槽扩展成保护区。
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        // 导航优先保持原有热区；后续动作最多使用导航之外的空间，避免两组互相覆盖。
        val navigationPlaceable = measurables[0].measure(looseConstraints)
        // 有界窗口为动作扣除导航宽度；无界预览保留自然测量，避免对无限宽做像素运算。
        val actionsMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - navigationPlaceable.width).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        // 动作宽度来自实际字体、图标和调用方插槽，标题不再猜测按钮数量。
        val actionsPlaceable = measurables[2].measure(looseConstraints.copy(maxWidth = actionsMaxWidth))
        // 标题固有宽度只用于选择同行或独立行；最终文本仍由当前宽度与字体真实排版。
        val titleNaturalWidth = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        // 标题与两侧操作之间保留的最小像素间距。
        val titleActionGap = TitleBarTitleActionGap.roundToPx()
        // 常规窗口占满可用宽度；无界预览按三段自然尺寸确定有限布局宽度。
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(
            navigationPlaceable.width + titleNaturalWidth + actionsPlaceable.width + titleActionGap * 2,
        )
        // 以较宽一侧对称避让，保持标题中心不随左右按钮数量变化。
        val sideWidth = maxOf(navigationPlaceable.width, actionsPlaceable.width)
        // 无操作时不留额外侧边；窄窗口保护区最多占满宽度，标题会转到下一行。
        val centeredTitleWidth = (width - if (sideWidth > 0) (sideWidth + titleActionGap) * 2 else 0).coerceAtLeast(0)
        // 只在确实存在两侧内容且标题不能自然放入中间时分行，避免一级页出现空白操作行。
        val titleOnSeparateRow = sideWidth > 0 && titleNaturalWidth > centeredTitleWidth
        // 首行高度至少容纳 48dp 热区，并允许调用方的大字体文本动作自然增高。
        val actionRowHeight = maxOf(TitleBarMinContentHeight.roundToPx(), navigationPlaceable.height, actionsPlaceable.height)
        // 分行标题获得全宽；同行标题使用对称保护后的区域，均不会挤压已测量的按钮。
        val titleMaxWidth = if (titleOnSeparateRow) width else centeredTitleWidth
        // 分行时只扣除已经占用的首行高度；无界高度保持自然测量，不裁剪放大后的行高。
        val titleMaxHeight = if (titleOnSeparateRow && constraints.hasBoundedHeight) {
            (constraints.maxHeight - actionRowHeight).coerceAtLeast(0)
        } else {
            constraints.maxHeight
        }
        // 每个子项只做一次正式 measure；固有尺寸查询不生成第二份标题内容或副作用。
        val titlePlaceable = measurables[1].measure(looseConstraints.copy(maxWidth = titleMaxWidth, maxHeight = titleMaxHeight))
        // 同行时所有内容垂直居中，分行时栏高由操作行与标题行相加，交由外层 Scaffold 留出空间。
        val height = constraints.constrainHeight(
            if (titleOnSeparateRow) actionRowHeight + titlePlaceable.height else maxOf(actionRowHeight, titlePlaceable.height),
        )
        // 放置仅消费本轮测量值；placeRelative 自动镜像 RTL 下的导航与动作，居中标题保持几何中心。
        layout(width, height) {
            // 分行时按钮只在首行居中，同行时随整体自然高度居中。
            val controlsHeight = if (titleOnSeparateRow) actionRowHeight else height
            navigationPlaceable.placeRelative(0, (controlsHeight - navigationPlaceable.height) / 2)
            actionsPlaceable.placeRelative(width - actionsPlaceable.width, (controlsHeight - actionsPlaceable.height) / 2)
            titlePlaceable.placeRelative(
                (width - titlePlaceable.width) / 2,
                if (titleOnSeparateRow) actionRowHeight else (height - titlePlaceable.height) / 2,
            )
        }
    }
}

/**
 * 一级页面标题栏。
 *
 * 列表页、我的页这类底部 Tab 的根页面没有返回语义，但仍需要展示当前页面身份；该组件复用通用标题栏的状态栏 padding、
 * 48dp 最小内容高度、动态增高和居中规则，只省略左侧返回按钮，避免一级页面误导用户可以返回上一级。
 */
@Composable
fun TopLevelTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TitleBar(
        modifier = modifier,
        navigation = {},
        title = {
            TitleBarText(text = title)
        },
        actions = actions
    )
}

/**
 * 标题栏文案组件。
 *
 * 所有普通标题、一级页面标题和管理态标题复用字号、字重和居中规则；长标题最多两行，异常超长内容仍有省略兜底。
 * 不固定文本高度，由容器随实际行高增长，避免大字体时文字上下缘被裁掉。
 */
@Composable
fun TitleBarText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    )
}

/** 标题栏默认返回按钮，集中维护返回图标尺寸和无障碍文案，避免各页面重复硬编码。 */
@Composable
private fun TitleBarBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, Modifier.size(48.dp)) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(BaseR.string.base_general_back)
        )
    }
}

@Preview(showBackground = true)
/** 标题栏预览，用于检查返回按钮、标题居中和状态栏 padding 的基础效果。 */
@Composable
private fun TitleBarPreview() {
    TitleBar(title = "测试", onBack = {})
}
