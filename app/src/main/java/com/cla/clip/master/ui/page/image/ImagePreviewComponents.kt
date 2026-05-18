package com.cla.clip.master.ui.page.image

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.master.ui.widget.SelectableImageTile
import com.cla.clip.master.ui.widget.SharedImagePreviewMeta
import com.cla.clip.master.ui.widget.SharedImagePreviewSheetContent
import com.cla.clip.master.ui.widget.rememberSharedImageRequest

/** 图片候选网格固定列数；提取页按用户筛选效率固定一行四张，避免自适应列宽在不同状态下跳动。 */
internal const val IMAGE_CANDIDATE_GRID_COLUMNS = 4

/**
 * 实时候选缩略图。
 *
 * 该适配层只负责把探测阶段候选转换为共享图片缩略图所需的请求模型；选择状态、预览动作和解码尺寸仍由页面维护。
 */
@Composable
internal fun LiveCandidateTile(
    candidate: ImageCandidateData,
    selected: Boolean,
    imageLoader: ImageLoader,
    failureTitle: String,
    onPreview: () -> Unit,
    onToggleSelected: () -> Unit,
    onDecodedSize: (Int?, Int?) -> Unit,
) {
    SelectableImageTile(
        model = rememberSharedImageRequest(
            url = candidate.url,
            referer = candidate.referer,
            userAgent = candidate.userAgent,
            cookie = candidate.cookie,
            preview = false,
        ),
        selected = selected,
        imageLoader = imageLoader,
        failureTitle = failureTitle,
        failureSubtitle = candidate.url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: candidate.url,
        onPreview = onPreview,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 已落库图片候选网格项。
 *
 * 组件不直接理解数据库选择规则，只把 `ImageExtractItemData` 转成共享缩略图请求，确保图片提取页的 UI 细节复用共享实现。
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
    SelectableImageTile(
        model = rememberSharedImageRequest(
            url = item.url,
            referer = item.referer,
            userAgent = item.userAgent,
            cookie = item.cookie,
            preview = false,
        ),
        selected = selected,
        imageLoader = imageLoader,
        onPreview = onPreview,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 单张已落库图片预览底部弹窗内容。
 *
 * 弹窗主体由共享图片预览组件提供；本函数负责把图片提取页的实体和元信息缓存适配到共享 UI 契约。
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
    SharedImagePreviewSheetContent(
        model = rememberSharedImageRequest(
            url = item.url,
            referer = item.referer,
            userAgent = item.userAgent,
            cookie = item.cookie,
            preview = true,
        ),
        imageUrl = item.url,
        displayWidth = meta.width ?: item.width,
        displayHeight = meta.height ?: item.height,
        meta = meta.toSharedPreviewMeta(),
        selected = selected,
        imageLoader = imageLoader,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 实时候选图片预览底部弹窗内容。
 *
 * 候选未落库时没有 item id，因此元信息和选择状态由页面按稳定候选 key 维护；共享组件只负责预览布局。
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
    SharedImagePreviewSheetContent(
        model = rememberSharedImageRequest(
            url = candidate.url,
            referer = candidate.referer,
            userAgent = candidate.userAgent,
            cookie = candidate.cookie,
            preview = true,
        ),
        imageUrl = candidate.url,
        displayWidth = meta.width ?: candidate.width,
        displayHeight = meta.height ?: candidate.height,
        meta = meta.toSharedPreviewMeta(),
        selected = selected,
        imageLoader = imageLoader,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 将图片提取页 ViewModel 的元信息模型转换为共享预览模型。
 *
 * ViewModel 保留页面缓存字段和加载状态；共享组件只需要展示用的宽高、类型与体积，避免反向依赖页面层状态对象。
 */
private fun ImagePreviewMeta.toSharedPreviewMeta(): SharedImagePreviewMeta {
    return SharedImagePreviewMeta(
        width = width,
        height = height,
        mimeType = mimeType,
        contentLength = contentLength,
    )
}
