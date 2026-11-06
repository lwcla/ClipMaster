package com.cla.clip.master.ui.page.image.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.widget.SharedImagePreviewMeta
import com.cla.clip.master.ui.widget.image.ImagePreviewMetadata

/** 候选预览的纯展示页脚；元信息读取留在适配层，本组件仅保存当前弹层滚动位置。 */
@Composable
internal fun CandidateImagePreviewFooter(
    meta: SharedImagePreviewMeta,
    imageUrl: String,
    isExtracting: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 有界页脚避免大字体挤掉图片视口；元信息与选择动作均可滚动到达。
    Column(modifier.fillMaxWidth().heightIn(max = 144.dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (isExtracting) Text(stringResource(R.string.base_general_image_extract_loading))
        ImagePreviewMetadata(meta, imageUrl)
        TextButton(onClick = onToggleSelected) {
            Text(stringResource(if (selected) R.string.base_general_remove_this_image else R.string.base_general_keep_this_image))
        }
    }
}
