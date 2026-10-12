package com.cla.clip.master.ui.widget.ad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.cla.clip.master.R

/**
 * 广告通用关闭外壳；先测量真实素材，零高或内容不允许时不组合按钮、不保留空白。
 * 自绘来源传入空回调时由卡片内部承载按钮；模板来源保留 48dp 外部控制区，副作用由宿主处理。
 */
@Composable
internal fun DismissibleAdLayout(
    presentable: Boolean,
    interactionEnabled: Boolean,
    /** 空回调表示来源已在卡片内承载控件，外壳只测量素材，不另占控制行。 */
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        // 素材按完整自然高度测量；空间是否足够由固定布局的上层决定，不能在此裁切广告。
        val ad = subcompose("ad") { Box { content() } }.single().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        // 未实际呈现时不建立关闭按钮的无障碍节点；广告子树继续独立加载有效素材。
        val control = if (presentable && ad.height > 0 && onDismiss != null) subcompose("dismiss") {
            IconButton(onClick = onDismiss, enabled = interactionEnabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.host_ad_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }.single().measure(Constraints(maxWidth = constraints.maxWidth)) else null
        // 内置按钮不增加额外高度；未呈现或零高素材仍保持零占位，不制造独立可点击白边。
        val height = if (presentable && ad.height > 0) ad.height + (control?.height ?: 0) else 0
        layout(ad.width, height) {
            if (height > 0) {
                control?.placeRelative((ad.width - control.width).coerceAtLeast(0), 0)
                ad.placeRelative(0, control?.height ?: 0)
            }
        }
    }
}

/** 独立样例验证控制区与素材分离；业务和 SDK 不参与 Preview。 */
@Preview(widthDp = 320)
@Composable
private fun DismissibleAdLayoutPreview() {
    MaterialTheme {
        DismissibleAdLayout(true, true, onDismiss = {}) {
            Surface { Text(stringResource(R.string.host_ad_preview), modifier = Modifier.size(280.dp, 120.dp)) }
        }
    }
}

/** 无素材状态不显示孤立按钮，便于独立检查零占位契约。 */
@Preview(widthDp = 320)
@Composable
private fun EmptyDismissibleAdLayoutPreview() {
    MaterialTheme { DismissibleAdLayout(false, false, onDismiss = {}) {} }
}
