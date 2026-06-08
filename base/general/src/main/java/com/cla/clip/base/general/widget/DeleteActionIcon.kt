package com.cla.clip.base.general.widget

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R

/**
 * 共享删除动作图标。
 *
 * 仅绘制列表菜单同款删除图标，不绑定按钮热区；图标加文字按钮应把 `contentDescription` 传 `null`，
 * 避免读屏重复播报图标和文字。
 */
@Composable
fun DeleteActionIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.error,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    /** 共享删除图标资源；资源自身保持中性色，由调用方通过 tint 匹配当前主题危险色。 */
    val deleteIconPainter = painterResource(R.drawable.base_general_ic_delete)
    Icon(
        painter = deleteIconPainter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}

/**
 * 共享删除图标按钮。
 *
 * 纯图标删除入口统一使用 48dp 热区和同款删除图标；调用方必须传入具体无障碍文案，
 * 例如“删除这条搜索历史”或“删除剪贴数据”，避免不同删除范围被读成同一语义。
 */
@Composable
fun DeleteIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.error,
    iconSize: Dp = 24.dp,
) {
    /** 图标按钮禁用时使用 Material 低强调色，避免不可点击入口仍呈现高风险可操作状态。 */
    val resolvedTint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp)
    ) {
        DeleteActionIcon(
            contentDescription = contentDescription,
            tint = resolvedTint,
            size = iconSize
        )
    }
}
