package com.cla.clip.master.ui.page.image

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import com.cla.clip.feature.ad.api.AdPlacement
import com.cla.clip.master.ui.widget.media.MediaAdGrid
import com.cla.clip.master.ad.PageAdSession
import com.cla.clip.master.ad.rememberPageAdSession
import com.cla.clip.master.ad.adInteractionGuard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.toast
import com.cla.clip.master.utils.ImageFolderOpenHelper
import com.cla.clip.master.utils.ImageFolderOpenHelper.ImageFolderOpenResult
import com.cla.clip.master.ui.widget.CenteredStateContent
import com.cla.clip.master.ui.widget.InlineErrorState
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.InlineSuccessState
import com.cla.clip.master.ui.widget.ProbeWebView
import com.cla.clip.master.ui.widget.SharedImagePreviewDefaults
import com.cla.clip.master.ui.widget.TitleBar
import com.cla.clip.master.ui.widget.rememberAnimatedImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 图片提取页只编排会话与用户动作；脚本扫描、选择记忆、批次读取各有独立职责。 */
@Composable
fun ImageExtractPage(
    imageExtractVm: ImageExtractVm = hiltViewModel(),
    pageUrl: String,
    pageName: String,
    onBack: () -> Unit,
) {
    // 页面上下文只用于短提示和系统相册入口。
    val context = LocalContext.current
    // 页面拥有 WebView 相关任务；下载提交本身由 VM 独立持有。
    val scope = rememberCoroutineScope()
    // 候选和批次共用滚动位置，提交不会把内容滚回首行。
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 提取和结果网格使用同一图片缓存/动图解码器。
    val imageLoader = rememberAnimatedImageLoader(context)
    // 当前扫描和辅助预览分别可取消，任何一个都不决定另一个能否结束。
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    // 重新提取有候选时必须让用户确认清空选择。
    var showRetryConfirm by remember { mutableStateOf(false) }
    // 60 秒后直接在当前网格工具栏解释长耗时。
    var showLongRunningHint by remember { mutableStateOf(false) }
    // 当前会话快照，所有 WebView 回调都绑定该轮次。
    val state = imageExtractVm.probeState
    // 广告身份由用户重新提取驱动，后台恢复不清空已有内容身份。
    val adSession = rememberPageAdSession(pageUrl to imageExtractVm.extractionId)

    /** 停止页面所属任务；WebView 由共享 AndroidView.onRelease 统一销毁。 */
    fun stopProbeJobs() {
        scanJob?.cancel()
        previewJob?.cancel()
        scanJob = null
        previewJob = null
    }
    /** 只有明确的重新提取才清空候选和滚动，生命周期恢复不调用。 */
    fun restartProbe() {
        stopProbeJobs()
        imageExtractVm.restartProbe()
        scope.launch { gridState.scrollToItem(0) }
    }
    // 暂停时关闭候选通道并移除 WebView；恢复仅重启尚未完成的探测。
    com.cla.clip.master.ui.widget.ProbeLifecycleEffect(
        onStart = imageExtractVm::resumeProbe,
        onStop = { stopProbeJobs(); imageExtractVm.pauseProbe() },
    )
    // 状态离开扫描时及时取消协程，防止销毁后继续执行 JS。
    DisposableEffect(state) { onDispose { stopProbeJobs() } }
    // 一轮最多 120 秒；无候选 10 秒失败，有候选到期自动冻结供用户确认。
    LaunchedEffect(state) {
        showLongRunningHint = false
        if (state is ImageProbeState.Probing) {
            runImageProbeBudget(
                hasCandidates = { imageExtractVm.snapshotProbingCandidates().isNotEmpty() },
                onLongRunning = { showLongRunningHint = true },
                onFinish = { imageExtractVm.finishProbe(state.sessionId) },
            )
        }
    }
    Scaffold(topBar = { TitleBar(stringResource(R.string.base_general_image_extract_title), onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).adInteractionGuard(adSession)) {
            if (state is ImageProbeState.Probing) {
                // User-Agent 在主线程缓存，网络回调不读取 WebView 设置。
                var userAgent by remember(state.sessionId) { mutableStateOf<String?>(null) }
                ProbeWebView(
                    targetUrl = pageUrl,
                    sessionId = state.sessionId,
                    modifier = Modifier.fillMaxSize().alpha(0f),
                    consumeUserTouch = true,
                    onWebViewReady = { userAgent = it.settings.userAgentString },
                    onDocumentStarted = { stopProbeJobs() },
                    onPageFinished = { view, _ ->
                        // 预览补全为有界旁路，手动停止或候选完成都无需等待它。
                        previewJob = scope.launch { imageExtractVm.saveWebViewLinkPreview(view, pageUrl) }
                        scanJob = scope.launch {
                            try {
                                // 自动触底必须有 DOM 图片证据，不能只凭网络占位图结束。
                                val domCandidates = collectImagesWithDoublePass(view, view.url ?: pageUrl, userAgent,
                                    imageExtractVm, state.sessionId)
                                imageExtractVm.finishProbe(state.sessionId, hasDomEvidence = domCandidates.isNotEmpty())
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                logD("ImageExtractPage") { "扫描结束 type=${error.javaClass.simpleName}" }
                                imageExtractVm.finishProbe(state.sessionId)
                            }
                        }
                    },
                    shouldInterceptRequest = { _, request ->
                        // 旧回调只携带旧轮次，VM 拒绝已关闭的候选。
                        val candidate = request.toNetworkCandidate(pageUrl, userAgent)
                        if (candidate != null && imageExtractVm.addNetworkCandidate(state.sessionId, candidate) != null) {
                            scope.launch { imageExtractVm.publishProbingCandidates(state.sessionId) }
                        }
                        null
                    },
                )
            }
            ImageExtractContent(
                state = state,
                adSession = adSession,
                viewModel = imageExtractVm,
                gridState = gridState,
                imageLoader = imageLoader,
                showLongRunningHint = showLongRunningHint,
                adsBlocked = showRetryConfirm,
                isSubmittingDownload = imageExtractVm.isSubmitting,
                onRetry = {
                    if (!imageExtractVm.isSubmitting) {
                        if (state is ImageProbeState.Extracted) {
                            scope.launch {
                                if (!imageExtractVm.retryDownload()) context.toast(R.string.base_general_download_history_retry_failed)
                            }
                        } else if (imageExtractVm.probingCandidates.isNotEmpty()) showRetryConfirm = true
                        else restartProbe()
                    }
                },
                onExtractAgain = { if (!imageExtractVm.isSubmitting) restartProbe() },
                onStopExtract = {
                    if (state is ImageProbeState.Probing) {
                        stopProbeJobs()
                        imageExtractVm.finishProbe(state.sessionId)
                    }
                },
                onConfirmDownload = { keys ->
                    scope.launch {
                        if (!imageExtractVm.createBatchAndStartDownload(pageUrl, pageName, keys)) {
                            context.toast(R.string.base_general_failed_to_create_the_download_task)
                        }
                    }
                },
                onOpen = { outputDir ->
                    if (ImageFolderOpenHelper.openDownloadedImageFolder(context, outputDir) == ImageFolderOpenResult.None) {
                        scope.launch { context.toast(R.string.base_general_no_available_app_to_open_image_folder) }
                    }
                },
            )
            if (showRetryConfirm) {
                RetryExtractConfirmDialog(onDismiss = { showRetryConfirm = false }, onConfirm = {
                    showRetryConfirm = false
                    restartProbe()
                })
            }
        }
    }
}

/**
 * 根据图片提取状态切换页面主体内容。
 *
 * 这里把探测中、失败、已提取后的批次状态分支集中起来，外层页面只需要负责 WebView 和导航级状态。
 */
@Composable
private fun ImageExtractContent(
    state: ImageProbeState,
    adSession: PageAdSession,
    viewModel: ImageExtractVm,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    imageLoader: coil3.ImageLoader,
    showLongRunningHint: Boolean,
    adsBlocked: Boolean,
    isSubmittingDownload: Boolean,
    onRetry: () -> Unit,
    onExtractAgain: () -> Unit,
    onStopExtract: () -> Unit,
    onConfirmDownload: (Set<String>) -> Unit,
    onOpen: (String?) -> Unit,
) {
    // 探测中已有候选和自动完成待确认共用同一个调用位置，避免状态分支切换时重建实时网格，
    // 导致用户取消的选择、预览弹窗状态和 LazyVerticalGrid 滚动位置在完成瞬间丢失。
    val shouldShowLiveSelection = when (state) {
        is ImageProbeState.Probing, ImageProbeState.Idle -> viewModel.probingCandidates.isNotEmpty()
        ImageProbeState.ReadyToDownload -> true
        else -> false
    }

    if (shouldShowLiveSelection) {
        LiveCandidateSelectionContent(
            candidates = viewModel.probingCandidates,
            adSession = adSession,
            adsBlocked = adsBlocked,
            viewModel = viewModel,
            gridState = gridState,
            imageLoader = imageLoader,
            showLongRunningHint = showLongRunningHint,
            isExtracting = state is ImageProbeState.Probing,
            isSubmittingDownload = isSubmittingDownload,
            onRetry = onRetry,
            onStopExtract = onStopExtract,
            onConfirmDownload = onConfirmDownload,
        )
    } else {
        when (state) {
            ImageProbeState.Idle -> Unit
            is ImageProbeState.Probing -> CenteredStateContent {
                InlineLoadingState(stringResource(R.string.base_general_image_extract_loading))
            }
            ImageProbeState.ReadyToDownload -> Unit
            ImageProbeState.Failed -> CenteredStateContent { FailedText(onRetry) }
            is ImageProbeState.Extracted -> BatchStatusContent(state, viewModel, onRetry, onExtractAgain, onOpen, adSession, gridState, imageLoader)
        }
    }
}

/**
 * 实时图片候选选择视图。
 *
 * 该视图只读取内存候选，不提前写数据库；新增候选默认选中，用户取消过的候选会记录到取消集合，
 * 因此 DOM 重扫或 URL 升级不会覆盖用户的选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveCandidateSelectionContent(
    candidates: List<ImageCandidateData>,
    adSession: PageAdSession,
    adsBlocked: Boolean,
    viewModel: ImageExtractVm,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    imageLoader: coil3.ImageLoader,
    showLongRunningHint: Boolean,
    isExtracting: Boolean,
    isSubmittingDownload: Boolean,
    onRetry: () -> Unit,
    onStopExtract: () -> Unit,
    onConfirmDownload: (Set<String>) -> Unit,
) {
    // 选择记忆由 VM 持有，页面只读取并发送用户事件。
    val selectedKeys = viewModel.selection.selectedKeys
    // 单张预览属于本次组合，不跨配置变化保留临时弹层。
    var previewCandidate by remember { mutableStateOf<ImageCandidateData?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LiveCandidateToolbar(
            isExtracting = isExtracting,
            isSubmittingDownload = isSubmittingDownload,
            selectedCount = selectedKeys.size,
            totalCount = candidates.size,
            onRetry = onRetry,
            onSelectAll = { viewModel.selection.selectAll(true) },
            onUnselectAll = { viewModel.selection.selectAll(false) },
            showLongRunningHint = showLongRunningHint,
            onStopExtract = onStopExtract,
            onConfirmDownload = { onConfirmDownload(selectedKeys.toSet()) }
        )

        MediaAdGrid(
            items = candidates,
            itemKey = { viewModel.candidateKey(it) },
            sessionKey = viewModel.extractionId,
            gridState = gridState,
            placement = AdPlacement.ImageExtractNative,
            adSession = adSession,
            blocked = adsBlocked || previewCandidate != null || isSubmittingDownload,
        ) { candidate ->
                // 候选身份只关联真实图片，广告条目没有选择键。
                val key = viewModel.candidateKey(candidate)
                // 用户选择只取当前候选集合，广告插入不改变数量。
                val selected = key in selectedKeys
                LiveCandidateTile(
                    candidate = candidate,
                    selected = selected,
                    imageLoader = imageLoader,
                    failureTitle = stringResource(R.string.base_general_image_load_failed),
                    onPreview = {
                        previewCandidate = candidate
                    },
                    onToggleSelected = {
                        viewModel.selection.setSelected(key, !selected)
                    },
                    onDecodedSize = { width, height -> viewModel.updateCandidateDecodedSize(key, width, height) }
                )
        }
    }

    // 当前预览快照用于安全展开底部弹层。
    val candidate = previewCandidate
    if (candidate != null) {
        // 预览请求与弹层和前台生命周期绑定，后台不保留 HEAD/Range 网络任务。
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(candidate, lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.loadCandidatePreviewMeta(candidate)
            }
        }
        // 预览候选身份仍使用业务键，与广告位置序号无关。
        val candidateKey = viewModel.candidateKey(candidate)
        // 优先已有元信息缓存，缺失时复用候选尺寸。
        val meta = viewModel.candidatePreviewMetaCache[candidateKey]
            ?: ImagePreviewMeta(width = candidate.width, height = candidate.height)
        // 预览弹层独立状态，退出时随组合树释放。
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // 预览/网格选择由真实图片集合决定。
        val selected = candidateKey in selectedKeys
        ModalBottomSheet(
            onDismissRequest = { previewCandidate = null },
            sheetState = sheetState,
            shape = SharedImagePreviewDefaults.SheetShape
        ) {
            CandidatePreviewSheetContent(
                candidate = candidate,
                meta = meta,
                selected = selected,
                imageLoader = imageLoader,
                onToggleSelected = {
                    viewModel.selection.setSelected(candidateKey, !selected)
                },
                onDecodedSize = { width, height -> viewModel.updateCandidateDecodedSize(candidateKey, width, height) }
            )
        }
    }
}


/**
 * 实时选择页顶部工具栏。
 *
 * 展示提取是否仍在运行、候选总数、已选数量和核心操作；停止提取或提交下载时会禁用会话与选择动作。
 */
@Composable
private fun LiveCandidateToolbar(
    isExtracting: Boolean,
    isSubmittingDownload: Boolean,
    selectedCount: Int,
    totalCount: Int,
    showLongRunningHint: Boolean,
    onRetry: () -> Unit,
    onSelectAll: () -> Unit,
    onUnselectAll: () -> Unit,
    onStopExtract: () -> Unit,
    onConfirmDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (isExtracting) R.string.base_general_image_extract_still_running else R.string.base_general_image_extract_done_waiting
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.base_general_image_live_count, selectedCount, totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    if (isExtracting) {
                        onStopExtract()
                    } else {
                        onConfirmDownload()
                    }
                },
                enabled = !isSubmittingDownload && (isExtracting || selectedCount > 0)
            ) {
                Text(
                    stringResource(
                        when {
                            isSubmittingDownload -> R.string.base_general_image_extract_submitting
                            isExtracting -> R.string.base_general_finish_image_extract
                            else -> R.string.base_general_confirm_download_selected_images
                        }
                    )
                )
            }
        }
        if (showLongRunningHint && isExtracting) {
            Text(stringResource(R.string.base_general_image_extract_long_running), style = MaterialTheme.typography.bodySmall)
        }
        if (!isExtracting && selectedCount == 0 && totalCount > 0) {
            Text(
                text = stringResource(R.string.base_general_image_select_at_least_one),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            TextButton(onClick = onRetry, enabled = !isSubmittingDownload) {
                Text(stringResource(R.string.base_general_re_extract_images))
            }
            TextButton(onClick = onSelectAll, enabled = !isSubmittingDownload && selectedCount < totalCount) {
                Text(stringResource(R.string.base_general_select_all))
            }
            TextButton(onClick = onUnselectAll, enabled = !isSubmittingDownload && selectedCount > 0) {
                Text(stringResource(R.string.base_general_unselect_all))
            }
        }
    }
}

/**
 * 重新提取确认弹窗。
 *
 * 当前候选和选择状态只存在页面内存中，重新提取会全部清空；已有候选未下载时必须让用户明确确认这个代价。
 */
@Composable
private fun RetryExtractConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.base_general_re_extract_images)) },
        text = { Text(stringResource(R.string.base_general_re_extract_images_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.base_general_sure))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.base_general_cancel))
            }
        }
    )
}

/**
 * 组装图片下载结果文案。
 *
 * 按成功、过滤、失败数量拼接批次结果，并可选追加“点击打开”提示；统一使用字符串资源，避免 UI 层硬编码中文。
 */
@Composable
internal fun buildImageDownloadResultText(batch: ImageExtractBatchData, includeOpenText: Boolean): String {
    // 汇总真实成功、失败和过滤数量，省略零值。
    val parts = buildList {
        if (batch.successCount > 0) add(stringResource(R.string.base_general_image_saved_count, batch.successCount))
        if (batch.filteredCount > 0) add(stringResource(R.string.base_general_image_filtered_count, batch.filteredCount))
        if (batch.failedCount > 0) add(stringResource(R.string.base_general_image_failed_count, batch.failedCount))
        if (isEmpty()) add(stringResource(R.string.base_general_image_download_failed))
    }
    // 使用本地化分隔符连接结果摘要。
    val separator = stringResource(R.string.base_general_text_separator)
    return parts.joinToString(separator) + if (includeOpenText) separator + stringResource(R.string.base_general_image_open_suffix) else ""
}

/**
 * 可点击的成功提示。
 *
 * 用于下载完成、部分完成或过滤完成后的结果展示；只有存在可查看下载内容时才传入 onOpen 让外层打开相册/文件查看入口。
 */
@Composable
internal fun SuccessText(text: String, onOpen: (() -> Unit)? = null) {
    InlineSuccessState(text = text, onClick = onOpen)
}

/**
 * 可点击的失败提示。
 *
 * 默认用于图片提取失败重试，也可传入批量下载失败统计文案；点击后由调用方决定是重新探测还是重新展示当前批次。
 */
@Composable
internal fun FailedText(onRetry: () -> Unit, text: String = stringResource(R.string.base_general_image_extract_failed_retry)) {
    InlineErrorState(text = text, onClick = onRetry)
}
