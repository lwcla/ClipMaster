package com.cla.clip.master.ui.widget.image

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cla.clip.master.R
import com.cla.clip.base.general.utils.logD
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/** 单图请求状态与图片身份分离；缩放状态不会因为重试或计数更新而重建。 */
private enum class PreviewImageLoadState { Loading, Loaded, Failed }

/**
 * Telephoto 单图适配；调用方按稳定 key 放入 Pager，库负责缩放、边界平移及分块解码。
 * active=false 时移除图片请求，保留轻量缩放状态，不在后台继续网络读取或动图播放。
 */
@Composable
internal fun ImagePreviewContent(
    request: ImageRequest,
    imageLoader: ImageLoader,
    active: Boolean,
    modifier: Modifier = Modifier,
    state: ZoomableImageState = rememberZoomableImageState(rememberZoomableState()),
    onOriginalSize: ((Int, Int) -> Unit)? = null,
) {
    // 重试只重建当前请求子组合，不清空同图缩放状态。
    var retry by remember(request) { mutableIntStateOf(0) }
    // 保持当前候选回调，不让异步元信息任务捕获过期页面闭包。
    val originalSizeCallback by rememberUpdatedState(onOriginalSize)
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 视口与真实图片尺寸比较，以长图按宽度展示并由 Telephoto 平移。
        val viewportRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        // 首次位图只解码到有界视口；高精度细节由 Telephoto 后续按需分块，避免 ORIGINAL 全图分配。
        val viewportWidthPx = constraints.maxWidth.coerceAtLeast(1)
        // 高度也显式限定，不能让单轴未指定触发原尺寸解码。
        val viewportHeightPx = constraints.maxHeight.coerceAtLeast(1)
        // 宽高只来自本次成功解码，用于长图策略；初始未知时完整适配。
        var decodedWidth by remember(request) { mutableIntStateOf(0) }
        // 高度与宽度同请求更新；仅用于比例判断，不作为原图尺寸展示。
        var decodedHeight by remember(request) { mutableIntStateOf(0) }
        // 长图从顶部按宽度展示，其余图片保持完整适配。
        val tallImage = decodedWidth > 0 && decodedHeight > 0 &&
            decodedWidth.toFloat() / decodedHeight < viewportRatio
        if (active) key(request, retry) {
            // 移出当前前台页会销毁此状态和请求，下次进入使用 Coil 缓存重新挂载。
            var loadState by remember { mutableStateOf(PreviewImageLoadState.Loading) }
            // 释放标志阻止取消边界附近的迟到回调回写已离场页面。
            var attached by remember { mutableStateOf(true) }
            // 只保留当前子组合的成功结果，离屏或重试后释放。
            var loadedResult by remember { mutableStateOf<SuccessResult?>(null) }
            DisposableEffect(Unit) { onDispose { attached = false } }
            // 元信息读取跟随图片请求生命周期；取消后不会继续回写，且不产生额外网络请求。
            LaunchedEffect(loadedResult) {
                // 当前成功结果只在本请求的子组合内有效。
                val result = loadedResult ?: return@LaunchedEffect
                if (originalSizeCallback != null) {
                    // 只接受确定的原图宽高；缓存不存在时保留页面现有信息。
                    val originalSize = readOriginalPreviewSize(result, imageLoader.diskCache)
                    if (originalSize != null) originalSizeCallback?.invoke(originalSize.width, originalSize.height)
                }
            }
            // listener 只转换低敏状态，保持调用方图片来源、请求头与缓存配置。
            val model = remember(request, retry, viewportWidthPx, viewportHeightPx) {
                request.newBuilder().size(viewportWidthPx, viewportHeightPx).listener(
                    onSuccess = { _, result ->
                        if (attached) {
                            decodedWidth = result.image.width
                            decodedHeight = result.image.height
                            loadState = PreviewImageLoadState.Loaded
                            // 采样后的尺寸仅用于宽高比，不回填为原图分辨率。
                            loadedResult = result
                        }
                    },
                    onError = { _, result ->
                        if (attached) {
                            loadState = PreviewImageLoadState.Failed
                            logD("ImagePreview") { "预览图片加载失败 type=${result.throwable.javaClass.simpleName} retryable=true" }
                        }
                    },
                ).build()
            }
            ZoomableAsyncImage(
                model = model,
                imageLoader = imageLoader,
                state = state,
                contentDescription = stringResource(R.string.image_preview_image),
                modifier = Modifier.fillMaxSize().testTag("image_preview_zoomable"),
                contentScale = if (tallImage) ContentScale.FillWidth else ContentScale.Fit,
                alignment = if (tallImage) Alignment.TopCenter else Alignment.Center,
            )
            when (loadState) {
                PreviewImageLoadState.Loading -> CircularProgressIndicator()
                PreviewImageLoadState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.image_preview_load_failed))
                    // 重试仅重新执行图片显示请求，不触发提取或下载任务。
                    TextButton(onClick = { retry += 1 }) { Text(stringResource(R.string.image_preview_retry)) }
                }
                PreviewImageLoadState.Loaded -> Unit
            }
        }
    }
}
