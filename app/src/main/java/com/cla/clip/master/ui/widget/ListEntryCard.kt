package com.cla.clip.master.ui.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens

/**
 * 通用入口卡片。
 *
 * 适用于“我的页入口”“设置入口”“管理入口”等图标 + 标题 + 说明 + 点击动作的列表卡片；组件只承载展示和点击，
 * 业务导航、统计数量和权限判断由调用方转换成文案和回调后传入。
 */
@Composable
internal fun ListEntryCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    /** 入口卡片间距 token，保证我的页、磁力入口和设置项使用同一节奏。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    ClipMasterCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.size(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
            }
            Spacer(Modifier.width(spacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
