package com.cla.clip.master.ui.page.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.master.ui.widget.SelectableImageTile
import com.cla.clip.master.ui.widget.SharedImagePreviewMeta
import com.cla.clip.master.ui.widget.image.ImagePreviewContent
import com.cla.clip.master.ui.widget.image.ImagePreviewMetadata
import com.cla.clip.master.ui.widget.image.SharedImagePreviewDialog
import com.cla.clip.master.ui.widget.media.snapshotMediaGridItems
import com.cla.clip.master.ui.widget.rememberSharedImageRequest

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
        failureSubtitle = null,
        onPreview = onPreview,
        onToggleSelected = onToggleSelected,
        onDecodedSize = onDecodedSize,
    )
}

/**
 * 实时候选的全屏预览适配。
 *
 * 候选未落库时没有 item id，因此元信息和选择状态由页面按稳定候选 key 维护；共享组件只负责预览布局。
 */
@Composable
internal fun CandidateImagePreviewDialog(
    candidates: List<ImageCandidateData>,
    initialKey: String,
    viewModel: ImageExtractVm,
    imageLoader: ImageLoader,
    isExtracting: Boolean,
    onDismiss: () -> Unit,
) {
    // 同一帧绑定 key 和候选，不能按可变列表对象身份 remember。
    val items = snapshotMediaGridItems(candidates, viewModel::candidateKey)
    SharedImagePreviewDialog(
        items = items,
        initialKey = "media:$initialKey",
        onDismiss = onDismiss,
        imageContent = { candidate, active ->
            ImagePreviewContent(
                request = rememberSharedImageRequest(candidate.url, candidate.referer, candidate.userAgent, candidate.cookie, true),
                imageLoader = imageLoader,
                active = active,
                // 原图尺寸读取不依赖采样位图，回写仍由当前候选版本校验。
                onOriginalSize = { width, height -> viewModel.updateCandidateDecodedSize(candidate, width, height) },
            )
        },
        footer = { candidate ->
            // 仅落定的当前图片读取元信息；离开前台、关闭或切图时取消底层 Call。
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(candidate, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.loadCandidatePreviewMeta(candidate) }
            }
            // 元信息读取由 VM 的版本门闩保护；选择与网格共用同一集合。
            val candidateKey = viewModel.candidateKey(candidate)
            // 缓存未就绪时使用候选已有尺寸，不阻塞图片显示。
            val meta = viewModel.candidatePreviewMetaCache[candidateKey]
                ?: ImagePreviewMeta(width = candidate.width, height = candidate.height)
            // 预览选择状态与网格使用同一个稳定身份集合。
            val selected = candidateKey in viewModel.selection.selectedKeys
            Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (isExtracting) Text(stringResource(R.string.base_general_image_extract_loading))
                ImagePreviewMetadata(meta.toSharedPreviewMeta(), candidate.url)
                // 预览中的移除只取消下载勾选，不改变图片集合和计数。
                TextButton(onClick = { viewModel.selection.setSelected(candidateKey, !selected) }) {
                    Text(stringResource(if (selected) R.string.base_general_remove_this_image else R.string.base_general_keep_this_image))
                }
            }
        },
    )
}

/** 批次入口只适配点击时的图片集合，沿用已有展示请求契约，不持有数据库观察。 */
@Composable
internal fun BatchImagePreviewDialog(
    items: List<ImageExtractItemData>,
    initialKey: String,
    itemKey: (ImageExtractItemData) -> String,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    SharedImagePreviewDialog(
        items = snapshotMediaGridItems(items, itemKey),
        initialKey = "media:$initialKey",
        onDismiss = onDismiss,
        imageContent = { item, active ->
            ImagePreviewContent(
                request = rememberSharedImageRequest(item.outputUri ?: item.url, item.referer, item.userAgent, item.cookie, true),
                imageLoader = imageLoader,
                active = active,
            )
        },
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
