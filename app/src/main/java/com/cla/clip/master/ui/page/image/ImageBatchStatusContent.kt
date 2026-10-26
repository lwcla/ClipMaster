package com.cla.clip.master.ui.page.image

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ui.widget.*
import com.cla.clip.master.ui.widget.media.MediaAdGrid
import com.cla.clip.master.ui.widget.media.MediaTaskTile

/** 批次页保留媒体内容，阶段变化只更新摘要；生命周期只约束观察，不取消 Worker。 */
@Composable
internal fun BatchStatusContent(
    state: ImageProbeState.Extracted,
    viewModel: ImageExtractVm,
    onRetry: () -> Unit,
    onExtractAgain: () -> Unit,
    onOpen: (String?) -> Unit,
    adSession: PageAdSession,
    gridState: LazyGridState,
    imageLoader: ImageLoader,
) {
    // 两路数据库流合成一次快照，初始未读与查询结果缺失分别展示。
    // 读取失败只重新订阅，不重建下载批次或重复入队。
    var readRevision by remember(state.batchId) { mutableIntStateOf(0) }
    val snapshotFlow = remember(viewModel, state.batchId, readRevision) { viewModel.observeBatchSnapshot(state.batchId) }
    // 生命周期停止数据库订阅，正在执行的后台 Worker 保持独立。
    val snapshot by snapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    // 纯展示模型不修改数据库，不借用临时文件完成数冒充已保存。
    val presentation = presentImageBatch(snapshot)
    // 批次汇总只用于标题、结果和操作资格。
    val batch = snapshot?.batch
    // 提交后 Room 首帧仍使用已选候选，不先把整块网格换成加载画面。
    val items = if (snapshot == null || presentation.phase == ImageBatchPhase.Queued) {
        viewModel.submittedCandidates.mapIndexed { index, candidate ->
            ImageExtractItemData(batchId = state.batchId, url = candidate.url, referer = candidate.referer,
                userAgent = candidate.userAgent, cookie = candidate.cookie, displayOrder = index,
                width = candidate.width, height = candidate.height)
        }
    } else snapshot!!.items
    // 预览只属于当前批次，切换新批次时关闭。
    var previewItem by remember(state.batchId) { mutableStateOf<ImageExtractItemData?>(null) }
    // 成功和部分成功仍遵循原广告资格，失败与缺失不展示广告。
    val placement = batch?.let { imageBatchAdPlacement(it.status, items.isNotEmpty()) }
    Column(Modifier.fillMaxSize()) {
        Text(batch?.pageName ?: stringResource(R.string.base_general_image_extract_title),
            style = MaterialTheme.typography.titleMedium, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
        when (presentation.phase) {
            ImageBatchPhase.Loading -> InlineLoadingState(stringResource(R.string.base_general_preparing_download))
            ImageBatchPhase.ReadError -> InlineErrorState(stringResource(R.string.base_general_media_read_failed))
            ImageBatchPhase.Missing -> InlineErrorState(stringResource(R.string.base_general_the_download_task_was_not_found))
            ImageBatchPhase.Queued -> InlineLoadingState(stringResource(R.string.base_general_media_task_queued))
            ImageBatchPhase.Downloading -> InlineLoadingState(stringResource(R.string.base_general_image_download_progress, presentation.processed, presentation.total))
            ImageBatchPhase.Publishing -> InlineLoadingState(stringResource(R.string.base_general_image_publish_progress, presentation.processed, presentation.total))
            ImageBatchPhase.Result -> if (batch != null) {
                if (batch.status == ImageExtractBatchData.STATUS_FAILED) {
                    InlineErrorState(buildImageDownloadResultText(batch, includeOpenText = false))
                } else {
                    SuccessText(buildImageDownloadResultText(batch, includeOpenText = batch.successCount > 0),
                        onOpen = if (batch.successCount > 0) ({ onOpen(batch.outputDir) }) else null)
                }
            }
        }
        if (presentation.phase == ImageBatchPhase.ReadError) {
            TextButton(onClick = { readRevision += 1 }) { Text(stringResource(R.string.base_general_click_retry)) }
        }
        if (presentation.phase == ImageBatchPhase.Result || presentation.phase == ImageBatchPhase.Missing) {
            Row {
                if (batch != null && batch.failedCount > 0) {
                    TextButton(onClick = onRetry, enabled = !viewModel.isSubmitting) {
                        Text(stringResource(if (viewModel.isSubmitting) R.string.base_general_preparing_download else R.string.base_general_media_retry_batch))
                    }
                }
                TextButton(onClick = onExtractAgain, enabled = !viewModel.isSubmitting) {
                    Text(stringResource(R.string.base_general_re_extract_images))
                }
            }
        }
        MediaAdGrid(items = items, itemKey = { viewModel.candidateKey(it.asCandidate()) },
            sessionKey = viewModel.extractionId, placement = placement, adSession = adSession,
            gridState = gridState, blocked = previewItem != null || viewModel.isSubmitting) { item ->
            MediaTaskTile(
                model = rememberSharedImageRequest(url = item.outputUri ?: item.url,
                    referer = item.referer, userAgent = item.userAgent, cookie = item.cookie,
                    preview = false, placeholderKey = item.url),
                imageLoader = imageLoader,
                status = stringResource(when (item.status) {
                    ImageExtractItemData.STATUS_SUCCESS -> R.string.base_general_download_completed
                    ImageExtractItemData.STATUS_FAILED -> R.string.base_general_download_failed
                    ImageExtractItemData.STATUS_FILTERED -> R.string.base_general_image_download_filtered
                    ImageExtractItemData.STATUS_TEMP_READY -> R.string.base_general_media_task_publishing
                    else -> R.string.base_general_media_task_pending
                }),
                name = item.finalName ?: (item.displayOrder + 1).toString(),
                onPreview = { previewItem = item },
            )
        }
    }
    previewItem?.let { item ->
        SharedImagePreviewBottomSheet(
            model = rememberSharedImageRequest(item.outputUri ?: item.url, item.referer, item.userAgent, item.cookie, true),
            imageLoader = imageLoader,
            onDismiss = { previewItem = null },
        )
    }
}

/** 薄适配复用候选稳定身份，数据库 id 产生后不改变网格 key。 */
private fun ImageExtractItemData.asCandidate() = ImageCandidateData(url, referer, userAgent, cookie, displayOrder, width, height)
