package com.cla.clip.master.ui.widget

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.gif.MovieDrawable
import coil3.gif.repeatCount
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Size
import coil3.size.SizeResolver
import com.cla.clip.base.general.R
import com.cla.clip.master.image.download.ImageRequestHeaderBuilder

/**
 * 共享图片预览组件的默认参数。
 *
 * 这些参数只描述图片缩略图和预览弹层的视觉边界，不包含图片提取、下载记录或相册业务语义。
 */
internal object SharedImagePreviewDefaults {
    /** 图片预览底部弹窗最多占屏高度比例，避免长图预览完全遮住页面上下文。 */
    const val SheetMaxHeightFraction: Float = 0.86f

    /** 图片预览最小宽高比，防止极端长图把预览区域压得过窄。 */
    const val MinAspectRatio: Float = 0.2f

    /** 图片预览最大宽高比，防止横幅图在底部弹窗中占用过高空白。 */
    const val MaxAspectRatio: Float = 4f

    /** 缩略图请求尺寸，单位为像素；列表和网格只加载小图以降低滚动成本。 */
    const val ThumbnailSizePx: Int = 420

    /** 图片预览底部弹窗形状，固定顶部圆角以保持和 Material 底部弹窗视觉一致。 */
    val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    /** 选择型图片缩略图形状，和选择描边共用以避免边框与图片裁剪不一致。 */
    val SelectableTileShape = RoundedCornerShape(8.dp)

    /** 普通媒体缩略图形状，用于下载记录页这类只预览不选择的图片入口。 */
    val ThumbnailShape = RoundedCornerShape(6.dp)
}

/**
 * 图片预览弹层展示的轻量元信息。
 *
 * 调用方可以从 DOM、数据库、Coil 解码结果或响应头填充这些字段；为空时共享组件统一展示“未知”。
 */
internal data class SharedImagePreviewMeta(
    /** 图片宽度，单位为像素；为空表示当前无法确认。 */
    val width: Int? = null,

    /** 图片高度，单位为像素；为空时只影响分辨率文案，不影响预览加载。 */
    val height: Int? = null,

    /** 图片 MIME 类型，优先来自响应头，也可以由调用方根据 URL 后缀兜底。 */
    val mimeType: String? = null,

    /** 图片体积，单位为字节；很多站点不会返回该字段，因此允许为空。 */
    val contentLength: Long? = null,
)

/**
 * 记住支持动图的 Coil ImageLoader。
 *
 * 使用调用方传入的 `Context` 构建并随 Composition 复用；Android 9 及以上走 ImageDecoder，
 * 低版本走 GifDecoder，保证 GIF 和系统支持的动图格式在缩略图/预览中尽量正常播放。
 */
@Composable
internal fun rememberAnimatedImageLoader(context: Context): ImageLoader {
    return remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                // API 28+ 的 ImageDecoder 支持 GIF、Animated WebP 和 Animated HEIF；低版本用 Movie 解 GIF。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .repeatCount(MovieDrawable.REPEAT_INFINITE)
            .build()
    }
}

/**
 * 记住带图片反盗链上下文的 Coil 请求。
 *
 * 预览请求保留原始尺寸，缩略图请求限制尺寸；两者都复用下载链路的图片 Accept，避免 UI 预览和 Worker
 * 因内容协商差异拿到不同动静态版本。
 */
@Composable
internal fun rememberSharedImageRequest(
    url: String,
    referer: String?,
    userAgent: String?,
    cookie: String?,
    preview: Boolean,
): ImageRequest {
    val context = LocalContext.current
    val size = if (preview) {
        SizeResolver.ORIGINAL
    } else {
        SizeResolver(Size(SharedImagePreviewDefaults.ThumbnailSizePx, SharedImagePreviewDefaults.ThumbnailSizePx))
    }
    return remember(url, referer, userAgent, cookie, preview) {
        ImageRequest.Builder(context)
            .data(url)
            .size(size)
            .allowHardware(false)
            .httpHeaders(buildImageNetworkHeaders(referer, userAgent, cookie))
            .build()
    }
}

/**
 * 可选择的图片缩略图。
 *
 * 主体点击用于预览，右上角图标独立切换选择状态；选择集合由调用方维护，组件只负责视觉、点击语义和解码尺寸回调。
 */
@Composable
internal fun SelectableImageTile(
    model: Any,
    selected: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggleSelected: () -> Unit,
    modifier: Modifier = Modifier,
    failureTitle: String? = null,
    failureSubtitle: String? = null,
    onDecodedSize: (Int?, Int?) -> Unit = { _, _ -> },
) {
    var loadFailed by remember(model) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(SharedImagePreviewDefaults.SelectableTileShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = SharedImagePreviewDefaults.SelectableTileShape
            )
            .clickable(onClick = onPreview)
    ) {
        AsyncImage(
            model = model,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                loadFailed = false
                onDecodedSize(state.result.image.width, state.result.image.height)
            },
            onError = { loadFailed = true },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (selected) 1f else 0.42f)
        )

        if (loadFailed && failureTitle != null) {
            ImageLoadFailureContent(title = failureTitle, subtitle = failureSubtitle)
        }

        ImageSelectionCheckIcon(
            selected = selected,
            onToggleSelected = onToggleSelected,
        )
    }
}

/**
 * 普通图片缩略图。
 *
 * 用于下载记录等只需要点击预览、不需要选择状态的媒体入口；固定尺寸由调用方传入，避免加载状态改变列表高度。
 */
@Composable
internal fun ImageThumbnailTile(
    model: Any,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(SharedImagePreviewDefaults.ThumbnailShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentScale = ContentScale.Crop,
        error = rememberVectorPainter(Icons.Default.BrokenImage),
        placeholder = rememberVectorPainter(Icons.Default.Image)
    )
}

/**
 * 单张图片预览底部弹窗。
 *
 * 适用于下载记录这种只展示图片本体的入口；图片按弹窗宽度完整排版，高图交给图片区域纵向滚动查看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedImagePreviewBottomSheet(
    model: Any,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 跳过半展开态，打开后直接给图片预览尽量多的垂直空间；超出屏幕的部分交给图片区域滚动。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 每次打开预览都使用独立滚动状态，避免上一张高图的滚动位置影响下一张图片。
    val imageScrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SharedImagePreviewDefaults.SheetShape,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.base_general_image_preview),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.base_general_sure))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(imageScrollState),
                contentAlignment = Alignment.TopCenter
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                    error = rememberVectorPainter(Icons.Default.BrokenImage),
                    placeholder = rememberVectorPainter(Icons.Default.Image)
                )
            }
        }
    }
}

/**
 * 带元信息和可选选择动作的图片预览内容。
 *
 * 图片提取页会传入选择状态和切换回调；普通预览入口可以不传选择动作，只复用图片、元信息和长图滚动布局。
 */
@Composable
internal fun SharedImagePreviewSheetContent(
    model: Any,
    imageUrl: String,
    displayWidth: Int?,
    displayHeight: Int?,
    meta: SharedImagePreviewMeta,
    imageLoader: ImageLoader,
    selected: Boolean? = null,
    onToggleSelected: (() -> Unit)? = null,
    onDecodedSize: (Int?, Int?) -> Unit = { _, _ -> },
) {
    val scrollState = rememberScrollState()
    val unknownText = stringResource(R.string.base_general_unknow)
    val resolutionText = formatResolution(displayWidth, displayHeight, unknownText)
    val fileTypeText = formatMimeType(meta.mimeType, imageUrl, unknownText)
    val fileSizeText = formatFileSize(meta.contentLength, unknownText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SharedImagePreviewDefaults.SheetMaxHeightFraction)
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.base_general_image_preview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.TopCenter
            ) {
                AsyncImage(
                    model = model,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state -> onDecodedSize(state.result.image.width, state.result.image.height) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .then(previewAspectModifier(displayWidth, displayHeight))
                )
            }
        }

        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                text = stringResource(R.string.base_general_image_resolution, resolutionText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.base_general_image_file_type, fileTypeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.base_general_image_file_size, fileSizeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(
                    text = imageUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected != null && onToggleSelected != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(onClick = onToggleSelected) {
                        Text(
                            stringResource(
                                if (selected) R.string.base_general_remove_this_image else R.string.base_general_keep_this_image
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 媒体不可读占位。
 *
 * 用于本地文件已删除、媒体 URI 不可读或缩略图集合为空的场景；只表达不可用状态，不决定是否重试或删除记录。
 */
@Composable
internal fun MediaUnavailablePlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(SharedImagePreviewDefaults.ThumbnailShape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 图片缩略图右上角选择图标。
 *
 * 仅承载选择态视觉和点击语义，业务选择集合由调用方维护，避免组件内部持有跨列表状态。
 */
@Composable
private fun BoxScope.ImageSelectionCheckIcon(
    selected: Boolean,
    onToggleSelected: () -> Unit,
) {
    Icon(
        imageVector = if (selected) Icons.Default.CheckCircleOutline else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(26.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .clickable(role = Role.Checkbox, onClick = onToggleSelected)
            .padding(2.dp)
    )
}

/**
 * 图片加载失败占位。
 *
 * 缩略图加载失败不代表最终下载一定失败，因此这里只给用户识别线索，不删除或禁用该图片候选。
 */
@Composable
private fun ImageLoadFailureContent(
    title: String,
    subtitle: String?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 构建图片加载请求头。
 *
 * Referer、User-Agent 和 Cookie 来自页面探测或历史记录，缺失时不强行补默认值，避免给站点发送误导性头信息。
 */
private fun buildImageNetworkHeaders(referer: String?, userAgent: String?, cookie: String?): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        // 与 Worker 下载请求保持一致，减少 CDN 因 Accept 不同返回静态预览或不同转码格式的概率。
        set("Accept", ImageRequestHeaderBuilder.IMAGE_REQUEST_ACCEPT)
        if (!referer.isNullOrBlank()) set("Referer", referer)
        if (!userAgent.isNullOrBlank()) set("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) set("Cookie", cookie)
    }.build()
}

/**
 * 根据图片尺寸生成预览宽高比约束。
 *
 * 未知尺寸时只限制最大高度；已知尺寸时把极端比例夹在可接受范围内，避免超长图或横幅图破坏底部弹窗布局。
 */
private fun previewAspectModifier(width: Int?, height: Int?): Modifier {
    if (width == null || height == null || width <= 0 || height <= 0) {
        return Modifier.heightIn(max = 560.dp)
    }
    val aspectRatio = (width.toFloat() / height.toFloat())
        .coerceIn(SharedImagePreviewDefaults.MinAspectRatio, SharedImagePreviewDefaults.MaxAspectRatio)
    return Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
}

/**
 * 格式化图片分辨率展示文案。
 *
 * 只有宽高都有效时才显示像素尺寸，否则显示“未知”，避免把 0 或缺失值误导性展示给用户。
 */
@Composable
private fun formatResolution(width: Int?, height: Int?, unknownText: String): String {
    return if (width != null && height != null && width > 0 && height > 0) {
        stringResource(R.string.base_general_image_resolution_value, width, height)
    } else {
        unknownText
    }
}

/**
 * 格式化图片类型展示文案。
 *
 * 优先使用响应头 MIME；缺失时从 URL 后缀推断，并把 `image/jpeg`、`svg+xml` 等技术值转换成用户更容易识别的格式名。
 */
private fun formatMimeType(mimeType: String?, url: String, unknownText: String): String {
    val type = mimeType?.substringAfter("image/", missingDelimiterValue = mimeType)?.uppercase()
        ?: url.substringBefore("?").substringBefore("#").substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() }
    return when (type) {
        "JPEG" -> "JPG"
        "SVG+XML" -> "SVG"
        null -> unknownText
        else -> type
    }
}

/**
 * 格式化图片体积展示文案。
 *
 * 体积单位根据字节数自动在 B、KB、MB、GB 之间切换；为空或非正数时显示“未知”，因为预览阶段不会完整下载图片来强算体积。
 */
@Composable
private fun formatFileSize(bytes: Long?, unknownText: String): String {
    if (bytes == null || bytes <= 0L) return unknownText
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> stringResource(R.string.base_general_file_size_gb, gb)
        mb >= 1 -> stringResource(R.string.base_general_file_size_mb, mb)
        kb >= 1 -> stringResource(R.string.base_general_file_size_kb, kb)
        else -> stringResource(R.string.base_general_file_size_bytes, bytes)
    }
}
