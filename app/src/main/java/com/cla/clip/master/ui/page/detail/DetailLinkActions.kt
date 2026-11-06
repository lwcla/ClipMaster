package com.cla.clip.master.ui.page.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.theme.ClipMasterThemeTokens

/**
 * 详情单链接快捷区与链接操作弹层共用的复制/提取动作组。
 * 动作按完整可用行宽测量，英文和大字体空间不足时换行；本层不持有链接、导航或页面状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailLinkActions(
    /** 沿用调用方已经判定的提取许可；不可提取链接仍保留复制入口。 */
    canExtract: Boolean,
    /** 点击复制后交给页面处理完整 URL，本层不读取或写入剪贴板。 */
    onCopyLink: () -> Unit,
    /** 视频提取入口，由页面保留原有导航和参数。 */
    onExtractVideo: () -> Unit,
    /** 图片提取入口，由页面保留原有导航和参数。 */
    onExtractImages: () -> Unit,
    /** 宿主传入宽度和外边距，组件内部不添加页面专属留白。 */
    modifier: Modifier = Modifier,
) {
    /** 复用详情操作区间距，同行和换行后的动作均保留可辨认间隔。 */
    val spacing = ClipMasterThemeTokens.tokens.spacing
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        DetailLinkActionText(
            label = stringResource(R.string.base_general_copy_link),
            onClick = onCopyLink,
        )
        // 提取资格仍来自原链接规则，只控制两个入口是否显示，不增加新的业务判断。
        if (canExtract) {
            DetailLinkActionText(
                label = stringResource(R.string.base_general_video_extract),
                onClick = onExtractVideo,
            )
            DetailLinkActionText(
                label = stringResource(R.string.base_general_image_extract),
                onClick = onExtractImages,
            )
        }
    }
}

/** 保持原主色文字按钮样式与点击语义，超长标签允许自然换行而不裁掉操作名称。 */
@Composable
private fun DetailLinkActionText(
    /** 当前系统语言的完整动作名称，不在组件中缩写或限制为单行。 */
    label: String,
    /** 原样转交调用方的动作，不持有业务对象或产生额外副作用。 */
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
