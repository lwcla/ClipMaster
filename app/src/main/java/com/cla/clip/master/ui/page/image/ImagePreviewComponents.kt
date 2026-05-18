package com.cla.clip.master.ui.page.image

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
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageCandidateData

/** 图片预览底部弹窗最多占屏高度比例，避免长图预览完全遮住页面上下文。 */
private const val IMAGE_PREVIEW_SHEET_MAX_HEIGHT_FRACTION = 0.86f

/** 图片预览最小宽高比，防止极端长图把预览区域压得过窄。 */
private const val IMAGE_PREVIEW_MIN_ASPECT_RATIO = 0.2f

/** 图片预览最大宽高比，防止横幅图在底部弹窗中占用过高空白。 */
private const val IMAGE_PREVIEW_MAX_ASPECT_RATIO = 4f

/** 缩略图请求尺寸，单位为像素；只加载小图以降低列表滚动时的内存和网络成本。 */
private const val IMAGE_PREVIEW_THUMBNAIL_SIZE_PX = 420

/** 图片候选网格固定列数；提取页按用户筛选效率固定一行四张，避免自适应列宽在不同状态下跳动。 */
internal const val IMAGE_CANDIDATE_GRID_COLUMNS = 4

/** 图片请求 Accept，尽量贴近浏览器图片加载，避免预览和 Worker 因内容协商差异拿到不同动静态版本。 */
private const val IMAGE_REQUEST_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

/** 图片预览底部弹窗形状，固定顶部圆角以保持和 Material 底部弹窗视觉一致。 */
internal val IMAGE_PREVIEW_SHEET_SHAPE = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** 图片网格缩略图形状，和选择描边共用以避免边框与图片裁剪不一致。 */
private val IMAGE_THUMBNAIL_SHAPE = RoundedCornerShape(8.dp)

/**
 * 实时候选缩略图。
 *
 * 缩略图失败不会移除候选，因为最终 Worker 带请求头下载仍可能成功；失败时显示占位和 URL 尾部供用户识别。
 */
@Composable
internal fun LiveCandidateTile(
    candidate: ImageCandidateData,
    selected: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    var loadFailed by remember(candidate.url) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(IMAGE_THUMBNAIL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = IMAGE_THUMBNAIL_SHAPE
            )
            .clickable(onClick = onPreview)
    ) {
        AsyncImage(
            model = buildImageRequest(candidate),
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

        if (loadFailed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.base_general_image_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = candidate.url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: candidate.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        ImageSelectionCheckIcon(
            selected = selected,
            onToggleSelected = onToggleSelected,
        )
    }
}

/**
 * 图片候选网格项。
 *
 * 主体点击打开预览弹窗，右上角图标独立切换选择状态；缩略图加载成功后把解码尺寸回传给 ViewModel，
 * 供预览弹窗展示更可靠的分辨率。
 */
@Composable
internal fun ImageCandidateTile(
    item: ImageExtractItemData,
    selected: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(IMAGE_THUMBNAIL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = IMAGE_THUMBNAIL_SHAPE
            )
            .clickable(onClick = onPreview)
    ) {
        AsyncImage(
            model = buildImageRequest(item, preview = false),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { state -> onDecodedSize(state.result.image.width, state.result.image.height) },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (selected) 1f else 0.42f)
        )

        ImageSelectionCheckIcon(
            selected = selected,
            onToggleSelected = onToggleSelected,
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
 * 单张已落库图片预览底部弹窗内容。
 *
 * 弹窗支持长图纵向滚动、动图播放和元信息展示；底部按钮允许在不关闭弹窗的情况下保留或移除当前图片。
 */
@Composable
internal fun ImagePreviewSheetContent(
    item: ImageExtractItemData,
    meta: ImagePreviewMeta,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    ImagePreviewSheetContentCore(
        imageRequest = buildImageRequest(item, preview = true),
        imageUrl = item.url,
        displayWidth = meta.width ?: item.width,
        displayHeight = meta.height ?: item.height,
        meta = meta,
        selected = selected,
        imageLoader = imageLoader,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 实时候选图片预览底部弹窗内容。
 *
 * 候选未落库时没有 item id，因此元信息和选择状态都通过稳定候选 key 在页面内存中维护；展示和已落库图片预览保持一致。
 */
@Composable
internal fun CandidatePreviewSheetContent(
    candidate: ImageCandidateData,
    meta: ImagePreviewMeta,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    ImagePreviewSheetContentCore(
        imageRequest = buildImageRequest(candidate, preview = true),
        imageUrl = candidate.url,
        displayWidth = meta.width ?: candidate.width,
        displayHeight = meta.height ?: candidate.height,
        meta = meta,
        selected = selected,
        imageLoader = imageLoader,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 预览弹窗通用骨架。
 *
 * 已落库图片和实时阶段候选只在请求对象、URL 与尺寸来源上不同；抽成同一个骨架可以保证两种入口的滚动、
 * 动图播放、元信息展示和选择按钮行为保持一致。
 */
@Composable
private fun ImagePreviewSheetContentCore(
    imageRequest: ImageRequest,
    imageUrl: String,
    displayWidth: Int?,
    displayHeight: Int?,
    meta: ImagePreviewMeta,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    val scrollState = rememberScrollState()
    val unknownText = stringResource(R.string.base_general_unknow)
    val resolutionText = formatResolution(displayWidth, displayHeight, unknownText)
    val fileTypeText = formatMimeType(meta.mimeType, imageUrl, unknownText)
    val fileSizeText = formatFileSize(meta.contentLength, unknownText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(IMAGE_PREVIEW_SHEET_MAX_HEIGHT_FRACTION)
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
                    model = imageRequest,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state -> onDecodedSize(state.result.image.width, state.result.image.height) },
                    onError = {},
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

/**
 * 记住支持动图的 Coil ImageLoader。
 *
 * 使用 `remember(context)` 避免每次重组都创建解码器；Android 9 及以上走 ImageDecoder，低版本走 GifDecoder，
 * 保证 GIF 和系统支持的动图格式在缩略图/预览中尽量正常播放。
 */
@Composable
internal fun rememberAnimatedImageLoader(context: android.content.Context): ImageLoader {
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
 * 构建预览和缩略图共用的图片请求。
 *
 * 缩略图请求限制尺寸以节省资源，预览请求保留原始尺寸以便查看长图和动图；两者都会携带反盗链请求头，
 * 避免 UI 预览和实际下载表现不一致。
 */
@Composable
private fun buildImageRequest(item: ImageExtractItemData, preview: Boolean): ImageRequest {
    val context = LocalContext.current
    val size = if (preview) {
        SizeResolver.ORIGINAL
    } else {
        SizeResolver(Size(IMAGE_PREVIEW_THUMBNAIL_SIZE_PX, IMAGE_PREVIEW_THUMBNAIL_SIZE_PX))
    }
    return remember(item.id, item.url, item.referer, item.userAgent, item.cookie, preview) {
        ImageRequest.Builder(context)
            .data(item.url)
            .size(size)
            .allowHardware(false)
            .httpHeaders(buildNetworkHeaders(item))
            .build()
    }
}

/**
 * 构建阶段性候选缩略图请求。
 *
 * 进度视图没有数据库图片项，因此直接使用候选中的反盗链上下文；缩略图尺寸固定为小图，避免长耗时页面额外消耗过多流量。
 */
@Composable
private fun buildImageRequest(candidate: ImageCandidateData, preview: Boolean = false): ImageRequest {
    val context = LocalContext.current
    val size = if (preview) {
        SizeResolver.ORIGINAL
    } else {
        SizeResolver(Size(IMAGE_PREVIEW_THUMBNAIL_SIZE_PX, IMAGE_PREVIEW_THUMBNAIL_SIZE_PX))
    }
    return remember(candidate.url, candidate.referer, candidate.userAgent, candidate.cookie, preview) {
        ImageRequest.Builder(context)
            .data(candidate.url)
            .size(size)
            .allowHardware(false)
            .httpHeaders(buildNetworkHeaders(candidate))
            .build()
    }
}

/**
 * 构建图片加载请求头。
 *
 * Referer、User-Agent 和 Cookie 来自 WebView 探测时记录的上下文，缺失时不强行补默认值，避免给站点发送误导性头信息。
 */
private fun buildNetworkHeaders(item: ImageExtractItemData): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        val referer = item.referer
        val userAgent = item.userAgent
        val cookie = item.cookie
        // 与 Worker 下载请求保持一致，减少 CDN 因 Accept 不同返回静态预览或不同转码格式的概率。
        set("Accept", IMAGE_REQUEST_ACCEPT)
        if (!referer.isNullOrBlank()) set("Referer", referer)
        if (!userAgent.isNullOrBlank()) set("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) set("Cookie", cookie)
    }.build()
}

/**
 * 构建阶段性候选图片加载请求头。
 *
 * 与正式图片项使用同一套 Referer/User-Agent/Cookie 规则，确保进度页缩略图和最终选择页尽量表现一致。
 */
private fun buildNetworkHeaders(candidate: ImageCandidateData): NetworkHeaders {
    return NetworkHeaders.Builder().apply {
        val referer = candidate.referer
        val userAgent = candidate.userAgent
        val cookie = candidate.cookie
        // 实时网格预览和最终下载使用同一 Accept，方便对比“预览可动”和“保存后静态”的真实原因。
        set("Accept", IMAGE_REQUEST_ACCEPT)
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
        .coerceIn(IMAGE_PREVIEW_MIN_ASPECT_RATIO, IMAGE_PREVIEW_MAX_ASPECT_RATIO)
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
