package com.cla.clip.master.ui.widget

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.theme.cardCornerShape

/**
 * ClipMaster 主要内容卡片的默认外壳参数。
 *
 * 这些默认值只表达“我的页面”已经确认的卡片外观：圆角、阴影、边框和内容内边距。
 * 调用方仍然可以传入自己的业务状态色，例如剪贴 item 的来源 App 色边框，避免公共组件覆盖页面语义。
 */
object ClipMasterCardDefaults {
    /**
     * 主要内容卡片统一使用的圆角。
     *
     * 继续复用主题层已有 `cardCornerShape`，避免项目里同时出现两套卡片圆角来源。
     */
    val shape: Shape
        @Composable get() = cardCornerShape

    /** 默认边框宽度；只负责卡片外壳描边，不接管业务状态色。 */
    val BorderWidth: Dp = 1.dp

    /** 默认内容内边距，对应我的页面入口卡片的内容节奏。 */
    val ContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 14.dp)

    /** 无内容内边距，用于 `ClipCard` 这类内部已经自行管理 Canvas、侧滑和内容布局的复杂卡片。 */
    val ZeroContentPadding: PaddingValues = PaddingValues(0.dp)

    /**
     * 默认阴影高度。
     *
     * 使用 Material3 的 `CardElevation`，让普通卡片和自定义手势卡片共享同一套阴影规则。
     */
    @Composable
    fun elevation(): CardElevation = CardDefaults.cardElevation(defaultElevation = 10.dp)

    /**
     * 默认边框色。
     *
     * 页面没有业务状态色时使用主题 outlineVariant；剪贴列表等页面可以传入自己的边框色。
     */
    @Composable
    fun borderColor(): Color = MaterialTheme.colorScheme.outlineVariant
}

/**
 * 普通内容卡片外壳。
 *
 * 适用于我的页面入口、下载记录等只有点击/长按语义的主要内容卡片。点击反馈放在圆角裁剪后的内部层，
 * 因此水波纹和按压态不会越过卡片圆角；外边距仍由调用方通过 `modifier.padding(...)` 控制。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipMasterCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = ClipMasterCardDefaults.shape,
    elevation: CardElevation = ClipMasterCardDefaults.elevation(),
    borderColor: Color = ClipMasterCardDefaults.borderColor(),
    borderWidth: Dp = ClipMasterCardDefaults.BorderWidth,
    contentPadding: PaddingValues = ClipMasterCardDefaults.ContentPadding,
    content: @Composable BoxScope.(shape: Shape) -> Unit,
) {
    val interactionModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }

    ClipMasterCardShell(
        modifier = modifier,
        shape = shape,
        elevation = elevation,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentPadding = contentPadding,
        interactionModifier = interactionModifier,
        content = content,
    )
}

/**
 * 自定义手势内容卡片外壳。
 *
 * 适用于 `ClipCard` 这类需要侧滑、快捷动作区、自定义按压分区和无障碍语义的复杂卡片。该入口不会额外
 * 挂载 `clickable` 或 `combinedClickable`，避免公共外壳抢占或重复触发调用方自己的手势。
 */
@Composable
fun ClipMasterGestureCard(
    modifier: Modifier = Modifier,
    shape: Shape = ClipMasterCardDefaults.shape,
    elevation: CardElevation = ClipMasterCardDefaults.elevation(),
    borderColor: Color = ClipMasterCardDefaults.borderColor(),
    borderWidth: Dp = ClipMasterCardDefaults.BorderWidth,
    contentPadding: PaddingValues = ClipMasterCardDefaults.ZeroContentPadding,
    content: @Composable BoxScope.(shape: Shape) -> Unit,
) {
    ClipMasterCardShell(
        modifier = modifier,
        shape = shape,
        elevation = elevation,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentPadding = contentPadding,
        interactionModifier = Modifier,
        content = content,
    )
}

/**
 * 公共卡片外壳的唯一绘制入口。
 *
 * 这里集中处理 ElevatedCard、圆角裁剪、边框和内容内边距；两个公开入口只决定是否添加点击/长按交互。
 */
@Composable
private fun ClipMasterCardShell(
    modifier: Modifier,
    shape: Shape,
    elevation: CardElevation,
    borderColor: Color,
    borderWidth: Dp,
    contentPadding: PaddingValues,
    interactionModifier: Modifier,
    content: @Composable BoxScope.(shape: Shape) -> Unit,
) {
    ElevatedCard(
        shape = shape,
        elevation = elevation,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(interactionModifier)
                .border(borderWidth, borderColor, shape)
                .padding(contentPadding),
        ) {
            content(shape)
        }
    }
}

/**
 * 展示默认内容卡片和自定义边框色卡片。
 *
 * 预览用于提醒后续维护者：公共组件统一的是外壳契约，业务颜色仍可由调用方传入。
 */
@Preview(showBackground = true)
@Composable
private fun ClipMasterCardPreview() {
    Column(modifier = Modifier.padding(16.dp)) {
        ClipMasterCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = "默认内容卡片")
        }

        ClipMasterCard(
            modifier = Modifier
                .padding(top = 12.dp)
                .width(220.dp),
            borderColor = MaterialTheme.colorScheme.primary,
        ) {
            Text(text = "自定义边框色卡片")
        }
    }
}
