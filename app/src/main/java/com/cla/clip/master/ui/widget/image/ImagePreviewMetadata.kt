package com.cla.clip.master.ui.widget.image

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cla.clip.base.general.R
import com.cla.clip.master.ui.widget.SharedImagePreviewMeta
import com.cla.clip.master.ui.widget.formatResolution
import com.cla.clip.master.ui.widget.formatMimeType
import com.cla.clip.master.ui.widget.formatFileSize

/** 只展示尺寸、格式和体积；复用既有格式化规则，选择动作由页面 slot 提供。 */
@Composable
internal fun ImagePreviewMetadata(meta: SharedImagePreviewMeta, imageUrl: String, modifier: Modifier = Modifier) {
    // 缺失字段统一沿用已有未知文案，不为体积读取完整图片。
    val unknown = stringResource(R.string.base_general_unknow)
    Column(modifier) {
        Text(stringResource(R.string.base_general_image_resolution, formatResolution(meta.width, meta.height, unknown)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.base_general_image_file_type, formatMimeType(meta.mimeType, imageUrl, unknown)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.base_general_image_file_size, formatFileSize(meta.contentLength, unknown)), style = MaterialTheme.typography.bodySmall)
    }
}
