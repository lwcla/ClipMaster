package com.cla.clip.master.ui.page.image

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.cla.clip.master.ad.PageAdSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.master.ui.widget.CenteredStateContent
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.SharedImagePreviewBottomSheet
import com.cla.clip.master.ui.widget.rememberSharedImageRequest
import com.cla.clip.master.ui.widget.media.MediaAdGrid
import com.cla.clip.master.ui.widget.media.MediaTaskTile

/** 批次页仅在可见生命周期观察已有记录，图片请求由惰性网格按可见项执行。 */
@Composable
internal fun BatchStatusContent(
    state: ImageProbeState.Extracted,
    viewModel: ImageExtractVm,
    onRetry: () -> Unit,
    onOpen: (String?) -> Unit,
    adSession: PageAdSession,
) {
    // 记住数据库 Flow 身份，避免每个进度重组重新订阅；离开前台自动停止收集。
    val batchFlow = remember(viewModel, state.batchId) { viewModel.observeBatch(state.batchId) }
    // 批次汇总来自既有 Room 观察，计数不依赖网格已加载的缩略图。
    val batch by batchFlow.collectAsStateWithLifecycle(initialValue = null)
    // 当前快照便于空值分支智能转换，不额外持久化页面状态。
    val current = batch
    if (current == null || current.status == ImageExtractBatchData.STATUS_EXTRACTED) {
        CenteredStateContent { InlineLoadingState(stringResource(R.string.base_general_preparing_download)) }
        return
    }
    if (current.status == ImageExtractBatchData.STATUS_FAILED || current.status == ImageExtractBatchData.STATUS_FILTERED) {
        // 纯失败和全部过滤没有成功媒体结果，保留原先无广告的重试/统计界面。
        CenteredStateContent {
            if (current.status == ImageExtractBatchData.STATUS_FAILED) {
                FailedText(onRetry, buildImageDownloadResultText(current, includeOpenText = false))
            } else {
                SuccessText(buildImageDownloadResultText(current, includeOpenText = false))
            }
        }
        return
    }
    // 复用当前批次元数据观察；不加载其他批次，不为广告扫描相册或探测远端信息。
    val itemsFlow = remember(viewModel, state.batchId) { viewModel.observeItems(state.batchId) }
    // 实际媒体项作为网格唯一数据源，广告不参与计数或任务状态。
    val items by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // 预览属于当前页面临时状态；退出页面或更换批次即清空。
    var previewItem by remember(state.batchId) { mutableStateOf<ImageExtractItemData?>(null) }
    // 阶段边界由同一会话连接标题操作和网格，所有按压都延迟广告高度变化。
    val placement = imageBatchAdPlacement(current.status, items.isNotEmpty())
    Column(modifier = Modifier.fillMaxSize()) {
        Text(current.pageName, style = MaterialTheme.typography.titleMedium, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
        if (current.status == ImageExtractBatchData.STATUS_DOWNLOADING) {
            InlineLoadingState(stringResource(R.string.base_general_image_download_progress,
                current.successCount + current.failedCount + current.filteredCount, current.totalCount))
        } else {
            SuccessText(buildImageDownloadResultText(current, includeOpenText = true)) { onOpen(current.outputDir) }
        }
        MediaAdGrid(
            items = items,
            itemKey = { it.id },
            sessionKey = state.batchId,
            placement = placement,
            adSession = adSession,
            blocked = previewItem != null,
        ) { item ->
            MediaTaskTile(
                model = rememberSharedImageRequest(
                    url = item.outputUri ?: item.url,
                    referer = item.referer,
                    userAgent = item.userAgent,
                    cookie = item.cookie,
                    preview = false,
                ),
                status = stringResource(when (item.status) {
                    ImageExtractItemData.STATUS_SUCCESS -> R.string.base_general_download_completed
                    ImageExtractItemData.STATUS_FAILED -> R.string.base_general_download_failed
                    ImageExtractItemData.STATUS_FILTERED -> R.string.base_general_download_history_filtered
                    ImageExtractItemData.STATUS_TEMP_READY -> R.string.base_general_media_task_publishing
                    else -> R.string.base_general_media_task_pending
                }),
                name = item.finalName ?: ((item.displayOrder + 1).toString()),
                onPreview = { previewItem = item },
            )
        }
    }
    previewItem?.let { item ->
        SharedImagePreviewBottomSheet(
            model = rememberSharedImageRequest(
                url = item.outputUri ?: item.url,
                referer = item.referer,
                userAgent = item.userAgent,
                cookie = item.cookie,
                preview = true,
            ),
            onDismiss = { previewItem = null },
        )
    }
}
