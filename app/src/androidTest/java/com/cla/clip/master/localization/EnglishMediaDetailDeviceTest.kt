package com.cla.clip.master.localization

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.cla.clip.base.general.R
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.entity.ClipShowEntity
import com.cla.clip.master.entity.VideoDownloadState
import com.cla.clip.master.ui.dialog.ClipDeleteChoiceDialog
import com.cla.clip.master.ui.page.detail.*
import com.cla.clip.master.ui.page.image.*
import com.cla.clip.master.ui.page.image.component.CandidateImagePreviewFooter
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbar
import com.cla.clip.master.ui.page.image.component.ImageCandidateToolbarState
import com.cla.clip.master.ui.page.image.component.ImageBatchContentLayout
import com.cla.clip.master.ui.page.image.component.ImageExtractPageScaffold
import com.cla.clip.master.ui.page.recycle.*
import com.cla.clip.master.ui.page.video.*
import com.cla.clip.master.ui.widget.ProbeWebFailure
import com.cla.clip.master.ui.widget.ProbeWebFailureKind
import com.cla.clip.master.ui.widget.SharedImagePreviewMeta
import com.cla.clip.master.ui.widget.CenteredStateContent
import com.cla.clip.master.ui.widget.InlineLoadingState
import com.cla.clip.master.ui.widget.image.ImagePreviewContent
import com.cla.clip.master.ui.widget.image.SharedImagePreviewDialog
import com.cla.clip.master.ui.widget.media.snapshotMediaGridItems
import com.cla.clip.master.ui.widget.media.MediaAdGrid
import org.junit.Test
import org.junit.runner.RunWith

/** 媒体、详情和回收站的生产展示回归；全部数据为合成值，所有业务回调均为空。 */
@RunWith(AndroidJUnit4::class)
class EnglishMediaDetailDeviceTest : EnglishScreenTestBase() {
    /** 重提确认保留完整代价说明和两个决定按钮。 */
    @Test fun imageRetryConfirmation() = verifyEnglish("image-retry", listOf("Extract again", "OK", "Cancel")) {
        RetryExtractConfirmDialog({}, {})
    }

    /** 尚无候选时使用真实居中状态容器，检查完整提取提示。 */
    @Test fun imageExtractionLoading() = verifyEnglish("image-extraction-loading") {
        CenteredStateContent { InlineLoadingState(stringResource(R.string.base_general_image_extract_loading)) }
    }

    /** 提取失败的重试文案与预览失败不同，单独覆盖。 */
    @Test fun imageExtractionFailure() = verifyEnglish("image-extraction-failed") { FailedText({}) }

    /** 探测期间长等待提示、计数和停止动作共同可读。 */
    @Test fun imageCandidateRunning() = verifyCandidate("image-running", ImageCandidateToolbarState(true, false, 185, 205, false, true, true, true))

    /** 无选择时错误提示不应挤掉禁用的下载操作。 */
    @Test fun imageCandidateEmptySelection() = verifyCandidate("image-unselected", ImageCandidateToolbarState(false, false, 0, 205, false, true, false, false))

    /** 提交状态保留明确的长按钮文案。 */
    @Test fun imageCandidateSubmitting() = verifyCandidate("image-submitting", ImageCandidateToolbarState(false, true, 205, 205, true, true, true, false))

    /** 初次批次加载只显示准备状态，不创建真实任务。 */
    @Test fun imageBatchLoading() = verifyBatch("image-batch-loading", ImageBatchPhase.Loading)

    /** 读取失败保持独立重试入口。 */
    @Test fun imageBatchReadFailure() = verifyBatch("image-batch-read-error", ImageBatchPhase.ReadError)

    /** 已删除批次仍可明确重新提取。 */
    @Test fun imageBatchMissing() = verifyBatch("image-batch-missing", ImageBatchPhase.Missing, endLabel = "Extract again")

    /** 排队长说明不得受网格布局影响。 */
    @Test fun imageBatchQueued() = verifyBatch("image-batch-queued", ImageBatchPhase.Queued)

    /** 大计数覆盖下载进度占位符。 */
    @Test fun imageBatchDownloading() = verifyBatch("image-batch-downloading", ImageBatchPhase.Downloading)

    /** 发布阶段英文说明比下载进度更长，单独覆盖。 */
    @Test fun imageBatchPublishing() = verifyBatch("image-batch-publishing", ImageBatchPhase.Publishing)

    /** 全成功统计和打开提示完整展示。 */
    @Test fun imageBatchSuccess() = verifyBatch("image-batch-success", ImageBatchPhase.Result,
        syntheticBatch(ImageExtractBatchData.STATUS_SUCCESS, success = 205), "Extract again")

    /** 部分成功同时显示三项统计及两个长动作，保护动作行换行。 */
    @Test fun imageBatchMixedResult() = verifyBatch("image-batch-mixed", ImageBatchPhase.Result,
        syntheticBatch(ImageExtractBatchData.STATUS_PARTIAL_SUCCESS, success = 185, failed = 10, filtered = 10), "Extract again")

    /** 真实有界页面同时放入三行长标题、混合统计和两项动作，网格不能退化为零高。 */
    @Test fun imageBatchLongTitleKeepsGridVisible() = verifyBatch("image-batch-long-title", ImageBatchPhase.Result,
        syntheticBatch(ImageExtractBatchData.STATUS_PARTIAL_SUCCESS, success = 185, failed = 10, filtered = 10)
            .copy(pageName = "Synthetic international image collection with a deliberately long title ".repeat(5)), "Extract again")

    /** 全失败结果保持重试批次和重新提取两个不同操作。 */
    @Test fun imageBatchFailure() = verifyBatch("image-batch-failed", ImageBatchPhase.Result,
        syntheticBatch(ImageExtractBatchData.STATUS_FAILED, failed = 205), "Extract again")

    /** 全过滤统计不借用失败说明。 */
    @Test fun imageBatchFiltered() = verifyBatch("image-batch-filtered", ImageBatchPhase.Result,
        syntheticBatch(ImageExtractBatchData.STATUS_FILTERED, filtered = 205), "Extract again")

    /** 继续探测时的预览页脚可滚动到取消选择，元信息来自内存。 */
    @Test fun imageCandidatePreviewSelected() = verifyCandidatePreview(true)

    /** 缺失元信息与保留图片动作使用同一真实全屏布局。 */
    @Test fun imageCandidatePreviewUnselected() = verifyCandidatePreview(false)

    /** 批次预览没有候选选择页脚，仍保留真实标题、关闭与大计数。 */
    @Test fun imageBatchPreview() = verifyEnglish("image-batch-preview", listOf("Image preview", "Close preview", "185 / 205")) {
        SharedImagePreviewDialog(snapshotMediaGridItems((1..205).toList()) { it.toString() }, "media:185", {},
            imageContent = { _, _ -> Box(Modifier.fillMaxSize()) }, modifier = Modifier.width(320.dp))
    }

    /** 真实加载组件用内存位图验证成功，不访问媒体或网络。 */
    @Test fun imagePreviewLoaded() = verifyEnglish("image-preview-loaded", listOf("Close preview"), exercise = { rule ->
        // 等待真实图片加载阶段结束，避免只截到圆形进度指示器。
        rule.waitUntil(5_000) { rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Could not load image").assertDoesNotExist()
    }) {
        SyntheticImagePreview(failed = false)
    }

    /** 无 Fetcher 的合成对象必然本地失败，覆盖错误和重新加载动作。 */
    @Test fun imagePreviewFailure() = verifyEnglish("image-preview-failed", listOf("Could not load image", "Reload image"), exercise = { rule ->
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Reload image").fetchSemanticsNodes().isNotEmpty() }
    }) { SyntheticImagePreview(failed = true) }

    /** 等待前台时保留入口文案。 */
    @Test fun videoProbeIdle() = verifyProbe("video-idle", ProbeState.Idle, endLabel = "Show extraction progress")

    /** 自动探测百分比与查看入口共同可读。 */
    @Test fun videoProbeLoading() = verifyProbe("video-loading", ProbeState.Probing(1), VideoProbePageState.Loading(1, 100), endLabel = "Show extraction progress")

    /** 长等待状态同时覆盖帮助说明、收起和重提按钮。 */
    @Test fun videoProbeLongRunning() = verifyProbe("video-long-running", ProbeState.Probing(1, VideoProbeMode.Interactive),
        VideoProbePageState.Loaded(1), VideoProbeNotice.LongRunning, "No video detected yet. Try playing it or extracting again")

    /** 恢复网页说明长且需保持可达。 */
    @Test fun videoProbeReloaded() = verifyProbe("video-reloaded", ProbeState.Probing(1, VideoProbeMode.Interactive),
        VideoProbePageState.Loaded(1), VideoProbeNotice.Reloaded, "Page reloaded. Play the video or interact with the page again")

    /** 拦截外部应用提示不得因大字裁剪。 */
    @Test fun videoProbeExternalBlocked() = verifyProbe("video-external", ProbeState.Probing(1, VideoProbeMode.Interactive),
        VideoProbePageState.Loaded(1), VideoProbeNotice.ExternalAppBlocked, "The page tried to open another app. You are still on the extraction page")

    /** 超时终态仍保留两个恢复操作。 */
    @Test fun videoProbeTimeout() = verifyProbe("video-timeout", ProbeState.Failed(VideoProbeFailure.Timeout))

    /** 确定站点错误仅显示安全资源文案。 */
    @Test fun videoProbePageFailure() = verifyProbe("video-page-failure", ProbeState.Failed(VideoProbeFailure.PageContent))

    /** HTTP 数值错误与长重试按钮共同布局。 */
    @Test fun videoProbeHttpFailure() = verifyProbe("video-http", ProbeState.Failed(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Http, 403))))

    /** 网络错误使用最长通用网络说明。 */
    @Test fun videoProbeNetworkFailure() = verifyProbe("video-network", ProbeState.Failed(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Network, -2))))

    /** 网络超时有独立短提示。 */
    @Test fun videoProbeNetworkTimeout() = verifyProbe("video-network-timeout", ProbeState.Failed(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Network, -8))))

    /** SSL 错误使用完整安全连接说明。 */
    @Test fun videoProbeSslFailure() = verifyProbe("video-ssl", ProbeState.Failed(VideoProbeFailure.PageLoad(ProbeWebFailure(ProbeWebFailureKind.Ssl, 3))))

    /** 交互模式错误继续保留网页帮助与收起操作。 */
    @Test fun videoProbeInteractiveFailure() = verifyProbe("video-interactive-error", ProbeState.Probing(1, VideoProbeMode.Interactive),
        VideoProbePageState.Error(1, ProbeWebFailure(ProbeWebFailureKind.Http, 503)), endLabel = "Play the video on the page to help detect it")

    /** 成功入口不会在本测试申请权限。 */
    @Test fun videoExtractSuccess() = verifyEnglish("video-success", listOf("Video URL extracted", "Download")) {
        VideoExtractSuccessContent(false, false, false, {})
    }

    /** 准备任务时长按钮仍完整。 */
    @Test fun videoExtractSubmitting() = verifyEnglish("video-submitting", listOf("Preparing download task...")) {
        VideoExtractSuccessContent(true, false, false, {})
    }

    /** 提交失败在成功提示下方展示错误，保留再试入口。 */
    @Test fun videoExtractSubmissionFailure() = verifyEnglish("video-submission-failed") {
        VideoExtractSuccessContent(false, true, false, {})
    }

    /** 下载进度包含三位百分比，使用真实状态布局。 */
    @Test fun videoDownloadProgress() = verifyEnglish("video-download-progress", listOf("Downloading 100%")) {
        VideoDownloadProgressContent(VideoDownloadState.Downloading(100))
    }

    /** 合并阶段与下载阶段独立覆盖。 */
    @Test fun videoMergeProgress() = verifyEnglish("video-merge-progress", listOf("Merging 100%")) {
        VideoMergeProgressContent(VideoDownloadState.Merging(100))
    }

    /** 播放入口仅挂载纯展示回调，不打开播放器。 */
    @Test fun videoDownloadSuccess() = verifyEnglish("video-download-success", listOf("Video downloaded. Tap to play.")) {
        VideoDownloadSuccessContent({})
    }

    /** 一次遍历每种既有失败文案，滚动到尾部也检查大字体排版。 */
    @Test fun videoDownloadFailures() = verifyEnglish("video-download-errors", exercise = { rule ->
        // 每种分类都滚入视口，避免只检查首尾而漏掉中间的长错误说明。
        listOf("Video download failed. Tap to retry.", "Download interrupted. Tap to retry.",
            "Could not load download task. Try again.",
            "The resource has expired or access is restricted. Go back and extract it again.",
            "Storage permission required", "Connection failed or timed out. Check your network and retry.",
            "Download task not found").forEachIndexed { index, label ->
            rule.onNodeWithText(label).performScrollTo()
            checkAndCapture("video-download-error-$index")
        }
    }) {
        ScrollableFixture {
            // 原因全部是合成稳定分类，既有映射负责文案，不传真实异常或 URL。
            listOf(null, "download_interrupted", "download_read_failed", "HTTP 403 fixture", "permission denied", "timeout").forEach { reason ->
                VideoDownloadFailureContent(VideoDownloadState.Failed(reason), retry = {})
            }
            VideoDownloadFailureContent(VideoDownloadState.Failed(null), taskMissing = true, retry = {})
        }
    }

    /** 全部链接弹层真实 LazyColumn 可达最后一个合成条目。 */
    @Test fun detailAllLinksSheet() = verifyEnglish("detail-all-links", allowEllipsis = { it.startsWith("Synthetic article") }, exercise = { rule ->
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(syntheticLink(12).summary))
    }) { DetailAllLinksBottomSheet((1..12).map(::syntheticLink), {}, {}) }

    /** 可提取链接弹层保留完整三动作与横向滚动 URL。 */
    @Test fun detailExtractableLinkSheet() = verifyEnglish("detail-link-actions", listOf("Copy link", "Extract video", "Extract images"), exercise = { rule ->
        rule.onNodeWithText("Extract images").performScrollTo()
    }) { DetailLinkActionBottomSheet(syntheticLink(1), {}, {}, {}, {}) }

    /** 不支持提取的链接显示解释，仅保留复制。 */
    @Test fun detailUnsupportedLinkSheet() = verifyEnglish("detail-link-unsupported", listOf("Copy link")) {
        DetailLinkActionBottomSheet(syntheticLink(1).copy(canExtract = false, type = DetailLinkType.Other), {}, {}, {}, {})
    }

    /** 来源过滤长动作原本单行裁剪，此例保护自然换行。 */
    @Test fun detailSourceBlockAction() = verifyEnglish("detail-source-action", listOf("Block future copies from this app")) {
        DetailSourceBlockSection(syntheticClip(), emptySet(), {})
    }

    /** 已屏蔽来源展示不同动作而不访问真实名单。 */
    @Test fun detailSourceUnblockAction() = verifyEnglish("detail-source-unblock-action", listOf("Unblock this app")) {
        DetailSourceBlockSection(syntheticClip(), setOf("test.synthetic.source"), {})
    }

    /** 屏蔽确认的长标题和说明全部取生产资源。 */
    @Test fun detailBlockConfirmation() = verifyEnglish("detail-block-confirm", listOf("OK", "Cancel")) {
        DetailSourceBlockConfirmDialog(DetailSourceBlockAction.Block("test.synthetic.source"), {}, {})
    }

    /** 取消屏蔽确认覆盖第二套资源。 */
    @Test fun detailUnblockConfirmation() = verifyEnglish("detail-unblock-confirm", listOf("OK", "Cancel")) {
        DetailSourceBlockConfirmDialog(DetailSourceBlockAction.Unblock("test.synthetic.source"), {}, {})
    }

    /** 详情共享删除选择必须完整呈现两个长操作。 */
    @Test fun detailDeleteChoice() = verifyEnglish("detail-delete-choice", listOf("Move to Recycle bin", "Delete permanently")) {
        ClipDeleteChoiceDialog(syntheticClip(), {}, {}, {})
    }

    /** 回收站选中计数不被长永久删除按钮挤压。 */
    @Test fun recycleSelectionActions() = verifyEnglish("recycle-selection", listOf("Selected: 185", "Delete permanently")) {
        RecycleBinSelectionBar(185, {})
    }

    /** 零选中保持操作布局，按钮禁用不改变文案。 */
    @Test fun recycleEmptySelection() = verifyEnglish("recycle-empty-selection", listOf("Selected: 0", "Delete permanently")) {
        RecycleBinSelectionBar(0, {})
    }

    /** 还原弹窗完整说明恢复范围。 */
    @Test fun recycleRestoreConfirmation() = verifyEnglish("recycle-restore", listOf("Restore", "Cancel")) {
        RestoreConfirmDialog(syntheticClip(), {}, {})
    }

    /** 清空确认与单条删除不同，显式覆盖不可恢复说明。 */
    @Test fun recycleClearConfirmation() = verifyEnglish("recycle-clear", listOf("Delete permanently", "Cancel")) {
        ConfirmPermanentActionDialog(true, stringResource(R.string.base_general_clear_recycle_bin),
            stringResource(R.string.base_general_clear_recycle_bin_message), {}, {})
    }

    /** 多选永久删除包含长标题与数量，不能借用清空文案。 */
    @Test fun recycleDeleteSelectionConfirmation() = verifyEnglish("recycle-delete-selection", listOf("Delete permanently", "Cancel")) {
        ConfirmPermanentActionDialog(true, stringResource(R.string.base_general_delete_selected_permanently),
            stringResource(R.string.base_general_delete_selected_permanently_message, 185), {}, {})
    }

    /** 大字体下设置弹层滚动可达保存，保留预设选项。 */
    @Test fun recycleRetentionPreset() = verifyRetention(30, "30", true)

    /** 最大合法自定义天数和长字段提示在同一生产弹层验证。 */
    @Test fun recycleRetentionCustom() = verifyRetention(7, "3650", true)

    /** 非法输入仍可看到禁用保存与取消，不调用任何写入。 */
    @Test fun recycleRetentionInvalid() = verifyRetention(7, "0", false)

    /** 仅提供测试滚动视口，内部始终为生产组件；不复制页面骨架或资源文案。 */
    @Composable private fun ScrollableFixture(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
    }

    /** 候选工具栏各状态复用同一生产入口，最后滚动到次要动作。 */
    private fun verifyCandidate(stage: String, state: ImageCandidateToolbarState) = verifyEnglish(stage, exercise = { rule ->
        rule.onNodeWithText("Extract again").performScrollTo()
    }) { ScrollableFixture { ImageCandidateToolbar(state, {}, {}, {}, {}, {}) } }

    /** 合成批次固定时间和空输出路径，仅用于资源占位符与布局。 */
    private fun syntheticBatch(status: String, success: Int = 0, failed: Int = 0, filtered: Int = 0) = ImageExtractBatchData(
        pageUrl = "https://example.invalid/fixture", pageName = "Synthetic image batch", status = status,
        totalCount = 205, successCount = success, failedCount = failed, filteredCount = filtered,
        createTime = 1L, updateTime = 1L,
    )

    /** 批次阶段使用真实生产有界页面；只对白名单文件标题允许省略，滚动摘要不能挤掉图片操作区。 */
    private fun verifyBatch(stage: String, phase: ImageBatchPhase, batch: ImageExtractBatchData? = null, endLabel: String? = null) =
        verifyEnglish(stage, allowEllipsis = { it == batch?.pageName }, exercise = { rule ->
            rule.onNodeWithTag("image_batch_grid").assertHeightIsAtLeast(96.dp)
            rule.onNodeWithContentDescription("Synthetic image 1").assertIsDisplayed()
            endLabel?.let { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            // 摘要滚动后图片仍在同屏，不能靠把整个页面一起滚动来掩盖高度分配问题。
            rule.onNodeWithTag("image_batch_grid").assertHeightIsAtLeast(96.dp)
            rule.onNodeWithContentDescription("Synthetic image 1").assertIsDisplayed()
        }) { SyntheticBatchPage(phase, batch) }

    /** 480dp 整页经生产 Scaffold 扣除标题和 Insets，再进入同一摘要/网格配重；位图槽无外部读取。 */
    @Composable private fun SyntheticBatchPage(phase: ImageBatchPhase, batch: ImageExtractBatchData?) {
        // 所有网格项复用一张极小内存位图，避免布局测试引入解码与网络差异。
        val bitmap = remember { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) } }
        // Compose 图片视图仅包装本测试位图，不创建文件或图片加载器。
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        ImageExtractPageScaffold(onBack = {}, modifier = Modifier.height(480.dp)) {
            ImageBatchContentLayout(summary = {
                ImageBatchStatusSummary(ImageBatchPresentation(phase, 185, 205), batch, false, {}, {}, {}, {})
            }, grid = {
                Box(Modifier.fillMaxSize().testTag("image_batch_grid")) {
                    MediaAdGrid(items = (1..24).toList(), itemKey = { it }, sessionKey = "synthetic-image-batch",
                        placement = null, blocked = false) { index ->
                        // 仅图片内容槽使用合成位图，四列、边距、滚动及固定底部区域全部沿用生产网格。
                        Image(image, contentDescription = "Synthetic image $index", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                }
            })
        }
    }

    /** 同一真实全屏容器覆盖已选/未选页脚，合成图片槽不会读取元信息。 */
    private fun verifyCandidatePreview(selected: Boolean) = verifyEnglish("image-candidate-preview-$selected", exercise = { rule ->
        rule.onNodeWithText(if (selected) "Remove this image" else "Keep this image").performScrollTo()
    }) {
        SharedImagePreviewDialog(snapshotMediaGridItems(listOf("fixture")) { it }, "media:fixture", {},
            imageContent = { _, _ -> Box(Modifier.fillMaxSize()) }, modifier = Modifier.width(320.dp), footer = {
                CandidateImagePreviewFooter(
                    if (selected) SharedImagePreviewMeta(12000, 9000, "image/png", 123456789L) else SharedImagePreviewMeta(),
                    "https://example.invalid/fixture", isExtracting = selected, selected = selected, onToggleSelected = {},
                )
            })
    }

    /** 仅在本测试组合持有位图和 Coil 加载器；销毁时释放，不接入共享业务缓存。 */
    @Composable private fun SyntheticImagePreview(failed: Boolean) {
        // 独立英文 Context 使真实图片组件与全屏窗口取得相同字号。
        val context = LocalContext.current
        // 请求对象要么是内存位图，要么是无 Fetcher 的 Unit，均无外部 IO。
        val data = remember(failed) { if (failed) Unit else Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888) }
        // 测试自有加载器不挂业务网络组件。
        val loader = remember { ImageLoader.Builder(context).build() }
        // 请求只随合成对象变化，避免重组重复分配。
        val request = remember(data) { ImageRequest.Builder(context).data(data).build() }
        DisposableEffect(loader) { onDispose { loader.shutdown() } }
        SharedImagePreviewDialog(snapshotMediaGridItems(listOf("fixture")) { it }, "media:fixture", {},
            imageContent = { _, active -> ImagePreviewContent(request, loader, active) }, modifier = Modifier.width(320.dp))
    }

    /** 视频状态只读取合成会话快照，长提示通过真实滚动视口验证末端可达。 */
    private fun verifyProbe(stage: String, state: ProbeState, pageState: VideoProbePageState = VideoProbePageState.Loading(),
        notice: VideoProbeNotice? = null, endLabel: String = "Extract again") =
        verifyEnglish(stage, listOf(endLabel), exercise = { rule -> rule.onNodeWithText(endLabel).performScrollTo() }) {
            ScrollableFixture { VideoProbeControls(state, pageState, notice, {}, {}, {}) }
        }

    /** 长摘要允许既有省略，原始 URL 只使用保留测试域名且没有查询串。 */
    private fun syntheticLink(index: Int) = DetailLinkUiState(
        "https://example.invalid/article/$index/long-synthetic-filename", "Synthetic article $index with a long readable filename",
        DetailLinkType.Web, true,
    )

    /** 合成剪贴没有图片、用户来源或真实时间，危险操作回调不写入仓库。 */
    private fun syntheticClip() = ClipShowEntity(1L, "Synthetic clipboard entry", 1L, 0L, 1L, "Just now",
        "test.synthetic.source", false, false, null, null, null)

    /** 输入与滚动只改变弹层局部状态，显式验证末端保存可达以及原有禁用边界。 */
    private fun verifyRetention(currentDays: Int, input: String, enabled: Boolean) =
        verifyEnglish("recycle-retention-$input", listOf("OK", "Cancel"), exercise = { rule ->
            rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(input)
            checkAndCapture("recycle-retention-$input-input")
            rule.onNodeWithText("OK").performScrollTo()
            if (enabled) rule.onNodeWithText("OK").assertIsEnabled() else rule.onNodeWithText("OK").assertIsNotEnabled()
        }) { RecycleBinSettingSheet(currentDays, {}, {}) }
}
