package com.cla.clip.master.ui.widget.clip

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R as BaseR
import com.cla.clip.master.ui.theme.ClipMasterTheme

/** 页面提供的成对文案，仅在组合内使用，不持久化或依赖反射。 */
@Immutable
internal data class ClipSwipePastTexts(
    /** 未达到执行阈值时说明继续拖动的动作。 */
    val continueText: String,
    /** 达到阈值时明确说明正常松手将执行的动作。 */
    val releaseText: String,
)

/**
 * 绘制第二段滑动提示，直接消费与松手相同的阶段，不保存手势状态或执行副作用。
 * 调用方通过 modifier 固定菜单后的排版区域与裁剪；文字起点对齐、垂直居中，随既有布局方向镜像。
 * 保留两行及系统字号，窄屏/大字体允许被正文部分遮挡或因空间不足省略。
 */
@Composable
internal fun ClipSwipePastHint(
    state: ClipSwipePastState,
    texts: ClipSwipePastTexts,
    modifier: Modifier = Modifier,
) {
    if (state == ClipSwipePastState.Hidden) return
    Box(modifier = modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = if (state == ClipSwipePastState.Ready) texts.releaseText else texts.continueText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (state == ClipSwipePastState.Ready) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 以整卡减菜单后的固定区域预览排版；未模拟前景遮挡，极限宽度允许省略。 */
@Preview(name = "360dp卡片的右侧区域", widthDp = 215, showBackground = true)
@Preview(name = "240dp卡片的右侧区域·双倍字", widthDp = 95, fontScale = 2f, showBackground = true)
@Preview(name = "右侧区域·深色", widthDp = 215, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ClipSwipePastHintPreview() {
    ClipMasterTheme(dynamicColor = false) {
        // 三组文案复用页面的公共资源，防止预览与实际提示不同步。
        val examples = listOf(
            ClipSwipePastTexts(stringResource(BaseR.string.base_general_continue_swipe_to_fold_clip), stringResource(BaseR.string.base_general_release_to_fold_clip)),
            ClipSwipePastTexts(stringResource(BaseR.string.base_general_continue_swipe_to_unfold_clip), stringResource(BaseR.string.base_general_release_to_unfold_clip)),
            ClipSwipePastTexts(stringResource(BaseR.string.base_general_continue_swipe_to_delete_permanently), stringResource(BaseR.string.base_general_release_to_delete_permanently)),
        )
        Column {
            // 每组展示两阶段排版，宽度固定为卡片减菜单，不随手指偏移变化。
            examples.forEach { texts ->
                ClipSwipePastHint(ClipSwipePastState.Dragging, texts, Modifier.fillMaxWidth().heightIn(min = 80.dp))
                ClipSwipePastHint(ClipSwipePastState.Ready, texts, Modifier.fillMaxWidth().heightIn(min = 80.dp))
            }
        }
    }
}
