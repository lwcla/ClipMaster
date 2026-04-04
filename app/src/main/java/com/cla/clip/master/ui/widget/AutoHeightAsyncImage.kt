package com.cla.clip.master.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.min

/** 根据图片高度自适应的Image，有一个最大高度 */
@Composable
fun AutoHeightAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 150.dp,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        val density = LocalDensity.current
        val painter = rememberAsyncImagePainter(model)
        val state by painter.state.collectAsState()

        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        val displayHeight = when (val s = state) {
            is AsyncImagePainter.State.Success -> {
                val image = s.result.image
                if (image.width > 0 && image.height > 0) {
                    val scaledHeight = maxWidthPx * image.height / image.width
                    with(density) { min(scaledHeight, maxHeightPx).toDp() }
                } else {
                    maxHeight
                }
            }

            else -> maxHeight
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(displayHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}