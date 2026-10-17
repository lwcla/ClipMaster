package com.cla.clip.master.ui.widget.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage

/** 下载任务媒体项；只展示实际缩略图、状态和名称，预览由调用方控制，不包含选择动作。 */
@Composable
internal fun MediaTaskTile(
    model: Any,
    status: String,
    name: String,
    onPreview: () -> Unit,
    imageLoader: coil3.ImageLoader,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onPreview)) {
        AsyncImage(
            model = model,
            imageLoader = imageLoader,
            contentDescription = name,
            placeholder = rememberVectorPainter(Icons.Default.Image),
            error = rememberVectorPainter(Icons.Default.BrokenImage),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
