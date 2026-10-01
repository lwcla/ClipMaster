package com.cla.clip.master.ui.widget

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cla.clip.master.R
import com.cla.clip.master.ui.theme.ClipMasterTheme

/** 列表行交互边界，用具名状态代替可点击、提交中等多个互相组合的 Boolean。 */
internal enum class SelectableAppRowInteraction { Available, Disabled, Submitting }

/** 共享应用行仅持有展示数据，调用方负责安装读取、持久勾选、稳定身份和提交操作。 */
internal data class SelectableAppRowState(
    /** 主标题允许空名或长文本，遵守调用方名称契约。 */
    val title: String,
    /** 副标题用于明确应用身份，组件不会解析或保存它。 */
    val subtitle: String,
    /** 扫描后的内存图标，null 或显示失败使用默认图标。 */
    val icon: Drawable?,
    /** 已提交选择状态，提交中不伪造成功勾选。 */
    val selected: Boolean,
    /** 当前允许交互、暂时禁用或正在提交。 */
    val interaction: SelectableAppRowInteraction,
)

/**
 * 可选择应用行，整行包含 Checkbox 的单一目标回调；提交中局部反馈，不做业务副作用。
 * UI state 由调用方映射，组件不依赖页面候选、ViewModel、Repository 或 NavController。
 */
@Composable
internal fun SelectableAppRow(
    state: SelectableAppRowState,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    /** 复用原默认 App 图标，保持读取失败时的原色兜底。 */
    val defaultIcon = painterResource(R.drawable.host_ic_app_icon_default)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = state.selected,
                enabled = state.interaction == SelectableAppRowInteraction.Available,
                role = Role.Checkbox,
                onValueChange = onSelectedChange,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = state.icon,
            contentDescription = null,
            error = defaultIcon,
            fallback = defaultIcon,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(state.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                state.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.interaction == SelectableAppRowInteraction.Submitting) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Checkbox(
            checked = state.selected,
            onCheckedChange = null,
            enabled = state.interaction == SelectableAppRowInteraction.Available,
        )
    }
}

/** 长内容、大字体与提交中状态预览，不读取真实安装应用。 */
@Preview(showBackground = true, widthDp = 280, fontScale = 1.5f)
@Preview(showBackground = true, widthDp = 320, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SelectableAppRowPreview() {
    ClipMasterTheme {
        SelectableAppRow(
            SelectableAppRowState(
                title = stringResource(R.string.host_preview_app_title),
                subtitle = stringResource(R.string.host_preview_app_package),
                icon = null,
                selected = true,
                interaction = SelectableAppRowInteraction.Submitting,
            ),
            // 预览不连接实际保存任务。
            onSelectedChange = {},
        )
    }
}
