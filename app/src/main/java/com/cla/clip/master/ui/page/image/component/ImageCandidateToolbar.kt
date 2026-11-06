package com.cla.clip.master.ui.page.image.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.widget.ToggleFilterChip
import com.cla.clip.master.ui.widget.TrailingFilterActionRow
import com.cla.clip.master.R as HostR

/** 候选操作区的展示快照；不持有页面 ViewModel、请求或广告状态。 */
internal data class ImageCandidateToolbarState(
    /** 是否仍在探测，决定主操作为结束提取还是下载。 */
    val isExtracting: Boolean,
    /** 下载提交期间冻结选择，避免提交快照被用户事件改变。 */
    val isSubmitting: Boolean,
    /** 当前有效候选中的选中数，用于摘要和下载资格。 */
    val selectedCount: Int,
    /** 真实候选总数，广告不参与计数。 */
    val totalCount: Int,
    /** 来自选择规则的非空全集判断，决定合并按钮文案。 */
    val allSelected: Boolean,
    /** 有目标可选或有手动例外待清除时允许批量操作。 */
    val canSelectAll: Boolean,
    /** 本轮持续过滤开关，默认关闭且不受全选按钮影响。 */
    val filterFailedEnabled: Boolean,
    /** 既有探测超时提示，仅在提取期间展示。 */
    val showLongRunningHint: Boolean,
)

/** feature 级候选工具栏；只展示快照并抛出用户事件，小屏下次要操作自然换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageCandidateToolbar(
    // 页面传入的不可变展示快照，组件不派生业务选择。
    state: ImageCandidateToolbarState,
    // 沿用页面重新提取及确认流程。
    onRetry: () -> Unit,
    // 调用方根据点击时最新选择决定全选或取消全部。
    onToggleSelectAll: () -> Unit,
    // 回传用户选择的持续过滤状态，由业务层处理现在与未来失败。
    onFilterFailedChange: (Boolean) -> Unit,
    // 停止探测但不创建下载任务。
    onStopExtract: () -> Unit,
    // 提交当前选择的下载快照。
    onConfirmDownload: () -> Unit,
    // 允许宿主提供布局与可访问性语义。
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (state.isExtracting) R.string.base_general_image_extract_still_running
                        else R.string.base_general_image_extract_done_waiting),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.base_general_image_live_count, state.selectedCount, state.totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                // 主操作沿用原流程；选择类动作不会停止探测或隐式提交。
                onClick = { if (state.isExtracting) onStopExtract() else onConfirmDownload() },
                enabled = !state.isSubmitting && (state.isExtracting || state.selectedCount > 0),
            ) {
                Text(stringResource(when {
                    state.isSubmitting -> R.string.base_general_image_extract_submitting
                    state.isExtracting -> R.string.base_general_finish_image_extract
                    else -> R.string.base_general_confirm_download_selected_images
                }))
            }
        }
        if (state.showLongRunningHint && state.isExtracting) {
            Text(stringResource(R.string.base_general_image_extract_long_running), style = MaterialTheme.typography.bodySmall)
        }
        if (!state.isExtracting && state.selectedCount == 0 && state.totalCount > 0) {
            Text(stringResource(R.string.base_general_image_select_at_least_one),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp))
        }
        TrailingFilterActionRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            actions = {
                FlowRow(horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = onRetry, enabled = !state.isSubmitting) {
                        Text(stringResource(R.string.base_general_re_extract_images))
                    }
                    TextButton(onClick = onToggleSelectAll, enabled = !state.isSubmitting && state.canSelectAll) {
                        Text(stringResource(if (state.allSelected) R.string.base_general_unselect_all else R.string.base_general_select_all))
                    }
                }
            },
            filter = {
                ToggleFilterChip(
                    label = stringResource(HostR.string.host_image_filter_failed),
                    accessibilityDescription = stringResource(HostR.string.host_image_filter_failed_description),
                    selected = state.filterFailedEnabled,
                    onSelectedChange = onFilterFailedChange,
                    enabled = !state.isSubmitting,
                )
            },
        )
    }
}

/** 大字体、部分选择及失败项样例，用于独立检查换行与计数。 */
@Preview(widthDp = 320, fontScale = 1.5f)
@Composable
private fun PartialCandidateToolbarPreview() {
    MaterialTheme {
        ImageCandidateToolbar(ImageCandidateToolbarState(false, false, 185, 205, true, true, true, false),
            {}, {}, {}, {}, {})
    }
}

/** 空集合不能全选，但可以提前开启过滤。 */
@Preview(widthDp = 360)
@Composable
private fun EmptyCandidateToolbarPreview() {
    MaterialTheme {
        ImageCandidateToolbar(ImageCandidateToolbarState(false, false, 0, 0, false, false, false, false),
            {}, {}, {}, {}, {})
    }
}
