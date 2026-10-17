package com.cla.clip.master.ui.page.image

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.master.utils.WebViewLinkPreviewExtractor
import com.cla.clip.master.work.DownloadImagesWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Inject

/**
 * 图片提取页状态。
 *
 * Probing 表示 WebView 仍在加载和扫描网页，Extracted 表示选中的候选已落库并提交下载，
 * Failed 用于提取超时、解析失败或没有可用图片时的重试入口。
 */
sealed interface ImageProbeState {
    /** 初始状态，尚未开始本轮 WebView 探测。 */
    data object Idle : ImageProbeState

    data class Probing(
        /** 本轮探测会话编号，用于区分旧超时回调和当前有效探测。 */
        val sessionId: Int
    ) : ImageProbeState

    /**
     * 已选图片已经落库，UI 根据批次 id 观察下载和发布结果。
     *
     * `count` 是本次确认提交的数量，筛选发生在落库之前。
     */
    data class Extracted(
        /** 当前图片提取批次 id，必须对应 `image_extract_batches` 中已存在的记录，UI 会用它继续观察图片项。 */
        val batchId: Long,

        /** 本次落库的候选图片数量，取值不小于 0，仅用于提取完成提示，即用户最终选择下载的数量。 */
        val count: Int
    ) : ImageProbeState

    /**
     * 探测已经停止但尚未进入下载。
     *
     * 自动触底完成后只关闭后台 WebView，并让用户在实时网格中做最后确认；此状态不对应数据库批次，
     * 因此不会把未确认候选写入下载记录或启动 Worker。
     */
    data object ReadyToDownload : ImageProbeState

    /** 提取失败或超时，页面展示重试入口。 */
    data object Failed : ImageProbeState
}

/**
 * 图片预览弹窗展示的轻量元信息。
 *
 * 分辨率来自 DOM 或 Coil 解码结果，文件类型和体积来自 URL 与响应头探测。体积可能为空，因为很多站点不会返回
 * Content-Length，预览阶段也不会为了拿体积而完整下载图片。
 */
data class ImagePreviewMeta(
    /** 图片宽度，单位为像素；为空表示 DOM、解码和响应头探测都暂时无法确认。 */
    val width: Int? = null,

    /** 图片高度，单位为像素；为空时 UI 显示未知分辨率，不影响图片预览和下载。 */
    val height: Int? = null,

    /** 图片 MIME 类型，优先来自响应头，失败时用 URL 后缀兜底；为空表示无法可靠判断。 */
    val mimeType: String? = null,

    /** 图片完整体积，单位为字节；服务端不返回 Content-Length 或 Content-Range 时保持为空。 */
    val contentLength: Long? = null,

    /** 当前是否正在请求元信息，用于避免同一图片在预览弹窗中重复发起 HEAD/Range 请求。 */
    val isLoading: Boolean = false,
)

/**
 * 图片提取页 ViewModel。
 *
 * 负责合并 WebView DOM 扫描和网络拦截得到的图片候选、创建图片提取批次、观察下载状态，
 * 并在预览阶段维护只存在于当前页面生命周期内的图片元信息缓存。
 *
 * @param appContext 应用级 Context，用于启动 WorkManager，避免持有页面 Context 造成生命周期泄漏。
 * @param imageExtractRepo 图片提取数据仓库，负责批次和图片项的 Room 读写。
 * @param okHttpClient 延迟获取的 OkHttpClient，用于预览元信息探测，避免 ViewModel 初始化时立即创建网络客户端。
 * @param linkPreviewExtractor WebView 链接预览补全器，用于在真实页面加载后回写列表卡片预览。
 */
@HiltViewModel
class ImageExtractVm @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    // 批次与图片项读写复用现有事务和下载记录协议。
    private val imageExtractRepo: ImageExtractRepository,
    // 客户端延迟取得，页面初始化不发网络请求。
    private val okHttpClient: Provider<OkHttpClient>,
    // 网页摘要旁路补写，不决定提取能否完成。
    private val linkPreviewExtractor: WebViewLinkPreviewExtractor,
) : ViewModel() {

    companion object {
        /** 日志标签，仅用于图片提取 ViewModel 内部调试，不展示给用户。 */
        private const val TAG = "ImageExtractVm"

        /** B 站图片 CDN 的公共路径特征，用于把原图和 `@...webp` 这类样式图合并为同一候选。 */
        private const val BILI_BFS_PATH_MARKER = "/bfs/"

        /** 知乎图片 CDN 的主域名后缀，用于识别 picx/pic1/pic4 等不同子域上的同一张 v2 图片。 */
        private const val ZHIHU_IMAGE_HOST_SUFFIX = "zhimg.com"

        /** 图片文件路径扩展名匹配，用于识别 `原图@样式后缀` 这种通用转码 URL。 */
        private val IMAGE_FILE_PATH_REGEX = Regex(""".*\.(?:jpe?g|png|webp|gif|avif|bmp|svg)$""", RegexOption.IGNORE_CASE)

        /** 知乎 v2 图片路径匹配，忽略 `_b`、尺寸前缀和文件扩展名后把同一资源变体合并到同一候选 key。 */
        private val ZHIHU_V2_IMAGE_PATH_REGEX = Regex("""/(?:\d+/)?(v2-[A-Za-z0-9]+)(?:_[A-Za-z0-9]+)?\.(?:jpe?g|png|webp|gif)(?:$|[?#])""", RegexOption.IGNORE_CASE)

        /** 图片请求 Accept，和页面预览、Worker 下载保持一致，避免 CDN 按客户端能力返回不同动静态版本。 */
        private const val IMAGE_REQUEST_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

        /** 实时候选发布到 Compose 的最短间隔，避免 WebView 高频网络回调造成网格重组抖动。 */
        private const val CANDIDATE_PUBLISH_INTERVAL_MS = 250L
    }

    /** 当前图片提取页面状态，驱动 UI 在探测中、已提取、失败重试之间切换。 */
    internal val probeSession = ImageProbeSession()

    /** 页面阶段只由会话控制器驱动。 */
    val probeState: ImageProbeState get() = probeSession.state

    /** 当前探测会话编号，递增后会触发页面重新加载 WebView 并丢弃旧会话的超时结果。 */
    val sessionId: Int get() = probeSession.sessionId

    /** 用户重新提取次数；后台恢复不改变网格和广告的内容身份。 */
    var extractionId by mutableIntStateOf(0)
        private set

    /** 候选选择跨组合重建保留，只有显式重新提取清空。 */
    internal val selection = ImageCandidateSelection()

    /** 下载提交跨配置变化防重。 */
    var isSubmitting by mutableStateOf(false)
        private set

    /** 等待 Room 首次返回时显示已确认的真实候选。 */
    var submittedCandidates by mutableStateOf<List<ImageCandidateData>>(emptyList())
        private set

    /**
     * 当前探测会话内已经发现的阶段性候选。
     *
     * 只用于加载页进度、阶段性图片查看和手动结束提取，不直接写入 Room；正式批次仅在用户确认下载时创建。
     */
    val probingCandidates = mutableStateListOf<ImageCandidateData>()

    /** 阶段性候选的线程安全锁，避免 WebView 网络拦截和 DOM 扫描同时合并候选时产生重复或状态错乱。 */
    private val probingCandidatesLock = Any()

    /**
     * 当前允许写入阶段性候选的探测会话 id。
     *
     * WebView 的网络拦截回调可能在页面重试、失败或销毁后才返回；用独立的会话门闩可以在后台线程里丢弃旧请求，
     * 避免上一轮候选混入新一轮 UI 或最终批次。
     */
    private var activeCandidateSessionId: Int = -1

    /** 当前会话已去重的阶段性候选池，UI 状态从这里复制快照，保证后台回调不会直接修改 Compose 集合。 */
    private val probingCandidateMap = linkedMapOf<String, ImageCandidateData>()

    /** 读取协作者无页面状态，只在弹层可见时创建实际网络请求。 */
    private val previewMetadataReader = ImagePreviewMetadataReader { okHttpClient.get() }

    /** 当前页面内的候选预览元信息缓存，实时网格未落库前用稳定候选 key 记录预览信息。 */
    val candidatePreviewMetaCache = mutableStateMapOf<String, ImagePreviewMeta>()

    /** 实时候选限频任务；持续请求也会每 250ms 发布最新快照。 */
    private var candidatePublishJob: Job? = null

    /** 可见时仅恢复未完成扫描，保留候选和选择；新轮次只替换回调身份。 */
    fun resumeProbe() {
        if (probeState != ImageProbeState.Idle) return
        probeSession.resume()
        synchronized(probingCandidatesLock) { activeCandidateSessionId = sessionId }
    }

    /** 不可见时关闭发布和回调，已有候选、确认结果及下载批次保持不变。 */
    fun pauseProbe() {
        closeCandidateUpdates(sessionId)
        candidatePublishJob?.cancel()
        candidatePublishJob = null
        probeSession.pause()
    }

    /** 用户明确重新提取时清空旧候选和选择，允许从任何批次或失败状态重新开始。 */
    fun restartProbe() {
        probeSession.restart()
        resetProbeSession(sessionId)
        selection.reset()
        candidatePreviewMetaCache.clear()
        submittedCandidates = emptyList()
        extractionId += 1
    }

    /** 停止扫描立即冻结内存结果，不等待预览缓存写入，也不隐式创建下载任务。 */
    fun finishProbe(expectedSessionId: Int, hasDomEvidence: Boolean = true) {
        if (!probeSession.accepts(expectedSessionId)) return
        // 最终快照先发布，防止尚在限频窗口内的图片被遗漏。
        val snapshot = snapshotProbingCandidates()
        publishProbingCandidatesImmediately(expectedSessionId, snapshot)
        closeCandidateUpdates(expectedSessionId)
        probeSession.finish(expectedSessionId, hasDomEvidence && snapshot.isNotEmpty())
    }

    /**
     * 新一轮 WebView 探测开始前清空旧候选。
     *
     * 重试时必须丢弃上一轮网络请求和阶段性候选，避免动态页面的迟到请求混入新批次。
     */
    fun resetProbeSession(newSessionId: Int) {
        candidatePublishJob?.cancel()
        candidatePublishJob = null
        synchronized(probingCandidatesLock) {
            activeCandidateSessionId = newSessionId
            probingCandidateMap.clear()
        }
        probingCandidates.clear()
    }

    /**
     * 关闭当前候选写入通道。
     *
     * 自动完成、手动结束或失败后会调用这里；已有快照不会被清空，便于后续保存或失败前判断，但迟到的网络/DOM 更新不再写入。
     */
    private fun closeProbeSession(expectedSessionId: Int) {
        synchronized(probingCandidatesLock) {
            if (activeCandidateSessionId == expectedSessionId) {
                activeCandidateSessionId = -1
            }
        }
    }

    /** 判断指定会话是否仍允许更新阶段性候选，供主线程发布 UI 快照前做二次校验。 */
    private fun isCandidateSessionActive(expectedSessionId: Int): Boolean {
        return synchronized(probingCandidatesLock) {
            activeCandidateSessionId == expectedSessionId
        }
    }

    /**
     * 合并网络拦截候选并发布阶段性快照。
     *
     * `shouldInterceptRequest` 可能运行在后台线程，因此这里只更新线程安全 Map；调用方随后回到主线程同步 Compose 状态。
     */
    fun addNetworkCandidate(expectedSessionId: Int, candidate: ImageCandidateData): List<ImageCandidateData>? {
        return synchronized(probingCandidatesLock) {
            if (activeCandidateSessionId != expectedSessionId) return@synchronized null
            if (candidate.url.isBlank()) return@synchronized probingCandidateMap.values.sortedBy { it.displayOrder }
            mergeProbingCandidateLocked(candidate.copy(displayOrder = probingCandidateMap.size))
            probingCandidateMap.values.toList()
        }
    }

    /**
     * 合并 DOM 扫描候选并发布阶段性快照。
     *
     * DOM 候选携带网页顺序，若同一 URL 先被网络层发现，这里会用 DOM 顺序补齐尺寸和更自然的展示位置。
     */
    fun addDomCandidates(expectedSessionId: Int, candidates: List<ImageCandidateData>): List<ImageCandidateData>? {
        return synchronized(probingCandidatesLock) {
            if (activeCandidateSessionId != expectedSessionId) return@synchronized null
            if (candidates.isEmpty()) return@synchronized probingCandidateMap.values.sortedBy { it.displayOrder }
            candidates.forEachIndexed { index, candidate ->
                mergeProbingCandidateLocked(candidate.copy(displayOrder = index))
            }
            // 实时网格使用插入顺序，避免每轮 DOM 扫描把用户正在筛选的图片重新排序。
            probingCandidateMap.values.toList()
        }
    }

    /** 将线程安全候选快照同步给 Compose 状态，只能在主线程调用。 */
    fun publishProbingCandidates(expectedSessionId: Int) {
        if (!isCandidateSessionActive(expectedSessionId)) return
        if (candidatePublishJob?.isActive == true) return
        candidatePublishJob = viewModelScope.launch(Dispatchers.Main) {
            delay(CANDIDATE_PUBLISH_INTERVAL_MS)
            if (!isCandidateSessionActive(expectedSessionId)) return@launch
            // 限频窗口结束取最新池快照，持续网络回调不会一直推迟首屏发布。
            val latest = snapshotProbingCandidates()
            if (probingCandidates != latest) {
                probingCandidates.clear()
                probingCandidates.addAll(latest)
            }
            selection.update(latest.map(::candidateKey))
        }
    }

    /** 在自动完成或手动结束前立即发布最终候选快照，避免防抖任务被关闭会话丢弃。 */
    fun publishProbingCandidatesImmediately(expectedSessionId: Int, candidates: List<ImageCandidateData>) {
        if (!isCandidateSessionActive(expectedSessionId)) return
        candidatePublishJob?.cancel()
        candidatePublishJob = null
        probingCandidates.clear()
        probingCandidates.addAll(candidates)
        selection.update(candidates.map(::candidateKey))
    }

    /** 返回当前阶段性候选快照，供手动结束或失败兜底判断使用。 */
    fun snapshotProbingCandidates(): List<ImageCandidateData> {
        return synchronized(probingCandidatesLock) {
            probingCandidateMap.values.toList()
        }
    }

    /**
     * 页面退出或 WebView 被主动销毁时关闭候选更新。
     *
     * 这里只截断网络/DOM 迟到回调，不改变 `probeState`，因为调用方可能随后用当前快照保存批次或切换到失败态。
     */
    fun closeCandidateUpdates(expectedSessionId: Int) {
        closeProbeSession(expectedSessionId)
    }

    /**
     * 使用当前 WebView DOM 补全链接预览缓存。
     *
     * 图片提取页能绕过部分 OkHttp/Jsoup 首轮 403 的限制，因为 WebView 已经按浏览器上下文加载了页面；
     * 这里只补写 `link_previews`，失败不会改变图片候选落库和下载流程。
     */
    suspend fun saveWebViewLinkPreview(webView: WebView, pageUrl: String, fallbackImageUrl: String? = null) {
        kotlinx.coroutines.withTimeoutOrNull(1_500L) {
            linkPreviewExtractor.extractAndSave(webView, pageUrl, fallbackImageUrl)
        }
    }

    /**
     * 从当前内存候选创建批次并启动图片下载。
     *
     * 实时选择阶段不提前写 Room；只有用户确认下载时才按稳定候选 key 取当前最优 URL 写库并入队 Worker。
     * 失败时返回 false，页面会保留候选和选择状态，允许用户再次尝试。
     */
    suspend fun createBatchAndStartDownload(
        pageUrl: String,
        pageName: String,
        selectedCandidateKeys: Set<String>,
    ): Boolean {
        if (probeState != ImageProbeState.ReadyToDownload || isSubmitting) return false
        // 在提交瞬间固定选中内容，网络和后续 UI 变化不再影响本批次。
        val selected = snapshotProbingCandidates()
            .filter { it.stableKey() in selectedCandidateKeys }
            .sortedBy { it.displayOrder }
            .mapIndexed { index, candidate -> candidate.copy(displayOrder = index) }
        if (selected.isEmpty()) return false
        return submitBatch { imageExtractRepo.createBatch(pageUrl, pageName, selected) to selected }
    }

    /** 重试下载克隆现有批次，不重新抓网页，不覆盖旧记录或旧媒体。 */
    suspend fun retryDownload(): Boolean {
        // 只重试当前已提交批次；重新提取使用另一条明确事件。
        val batch = probeState as? ImageProbeState.Extracted ?: return false
        return submitBatch {
            // 用仓库既有克隆能力复制请求上下文；无记录时保留原页面并报告失败。
            val newId = imageExtractRepo.cloneBatchForRetry(batch.batchId) ?: return@submitBatch null
            newId to submittedCandidates
        }
    }

    /** 提交属于 VM 生命周期，旋转不会取消半途写入或丢失新批次身份；主线程防重。 */
    private suspend fun submitBatch(create: suspend () -> Pair<Long, List<ImageCandidateData>>?): Boolean {
        if (isSubmitting) return false
        isSubmitting = true
        return viewModelScope.async {
            try {
                // IO 只负责落库和入队，完成后回主线程发布界面快照。
                val result = withContext(Dispatchers.IO) {
                    val created = create() ?: return@withContext null
                    DownloadImagesWorker.enqueue(appContext, created.first)
                    created
                } ?: return@async false
                submittedCandidates = result.second
                probeSession.submitted(result.first, result.second.size)
                logD(TAG) { "图片下载已提交 batchId=${result.first} count=${result.second.size}" }
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logD(TAG) { "图片下载提交失败 type=${error.javaClass.simpleName}" }
                false
            } finally {
                isSubmitting = false
            }
        }.await()
    }

    /** 等两路数据库快照到齐后再发布，初始加载与查无批次明确区分。 */
    internal fun observeBatchSnapshot(batchId: Long): Flow<ImageBatchSnapshot> = combine(
        imageExtractRepo.observeBatch(batchId), imageExtractRepo.observeItems(batchId),
    ) { batch, items -> ImageBatchSnapshot(batch, items) }.catch { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        logD(TAG) { "批次读取失败 batchId=$batchId type=${error.javaClass.simpleName}" }
        emit(ImageBatchSnapshot(null, emptyList(), readFailed = true))
    }

    /** 实时网格缩略图或预览解码成功后回填尺寸，仅在当前页面内存中使用。 */
    fun updateCandidateDecodedSize(candidateKey: String, width: Int?, height: Int?) {
        if (candidateKey.isBlank() || width == null || height == null || width <= 0 || height <= 0) return
        // 已有候选或元信息只用于合并，不丢弃此前有效字段。
        val old = candidatePreviewMetaCache[candidateKey] ?: ImagePreviewMeta()
        if (old.width == width && old.height == height) return
        candidatePreviewMetaCache[candidateKey] = old.copy(width = width, height = height)
    }

    /** 只在预览可见时挂起读取，关闭弹层或离开前台立即取消底层 HTTP 请求。 */
    suspend fun loadCandidatePreviewMeta(candidate: ImageCandidateData) {
        // 稳定身份保持 URL 升级前后的尺寸缓存一致。
        val candidateKey = candidate.stableKey()
        // 已有完整元信息无需重复联网。
        val cached = candidatePreviewMetaCache[candidateKey]
        if (cached?.isLoading == true || cached?.mimeType != null && cached.contentLength != null) return
        candidatePreviewMetaCache[candidateKey] = (cached ?: ImagePreviewMeta()).copy(
            width = cached?.width ?: candidate.width, height = cached?.height ?: candidate.height,
            mimeType = cached?.mimeType ?: guessMimeTypeFromUrl(candidate.url), isLoading = true,
        )
        try {
            // 三秒只限制辅助信息，不影响预览图片显示或主提取流程。
            val meta = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                previewMetadataReader.read(candidate)
            } ?: ImagePreviewMeta()
            candidatePreviewMetaCache[candidateKey] = mergeMeta(candidatePreviewMetaCache[candidateKey], meta)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logD(TAG) { "预览元信息不可用 type=${error.javaClass.simpleName}" }
        } finally {
            // 不恢复已经被重新提取清空的缓存；下次打开可重新请求。
            candidatePreviewMetaCache[candidateKey]?.let { current ->
                candidatePreviewMetaCache[candidateKey] = current.copy(isLoading = false)
            }
        }
    }

    /**
     * 合并单个阶段性候选。
     *
     * DOM 候选优先保留网页顺序和尺寸；网络候选补充 Referer、User-Agent、Cookie 等下载上下文。
     */
    private fun mergeProbingCandidateLocked(candidate: ImageCandidateData) {
        val url = candidate.url.trim()
        if (url.isBlank()) return
        // 候选身份归一化后供去重和选择状态共同使用。
        val key = candidate.dedupKey()
        // 已有候选或元信息只用于合并，不丢弃此前有效字段。
        val old = probingCandidateMap[key]
        probingCandidateMap[key] = if (old == null) {
            candidate
        } else {
            mergePreferredCandidate(old, candidate)
        }
    }

    /** 对外暴露实时选择使用的稳定候选 key，保证 UI 选择状态和内部去重规则保持一致。 */
    fun candidateKey(candidate: ImageCandidateData): String {
        return candidate.stableKey()
    }

    /**
     * 合并同一图片资源的两个候选。
     *
     * 下载 URL 优先选择 GIF 原图或无样式后缀的原始地址；展示顺序取两者更靠前的位置，请求上下文和尺寸尽量互补。
     * 如果页面中存在多个不同动图，它们的原始路径不同，会落在不同去重键上，不会互相覆盖。
     */
    private fun mergePreferredCandidate(old: ImageCandidateData, candidate: ImageCandidateData): ImageCandidateData {
        val preferred = preferredDownloadCandidate(old, candidate)
        // 非首选候选仍可补充尺寸与下载所需请求头。
        val other = if (preferred === old) candidate else old
        return preferred.copy(
            referer = preferred.referer ?: other.referer,
            userAgent = preferred.userAgent ?: other.userAgent,
            cookie = preferred.cookie ?: other.cookie,
            displayOrder = minOf(old.displayOrder, candidate.displayOrder),
            width = preferred.width ?: other.width,
            height = preferred.height ?: other.height,
        )
    }

    /**
     * 判断两个同源候选中哪个更适合最终下载。
     *
     * 一些图片 CDN 会把原始 GIF 以 `xxx.gif@...webp` 形式转码给页面组件；候选预览可能仍会动，
     * 但系统相册对转码格式支持不稳定，因此优先保存原始 `.gif` 地址，其次选择没有 `@` 样式后缀的原图地址。
     */
    private fun preferredDownloadCandidate(first: ImageCandidateData, second: ImageCandidateData): ImageCandidateData {
        val firstScore = first.downloadPriorityScore()
        // 新地址只有严格优于旧地址时才升级，避免同分抖动。
        val secondScore = second.downloadPriorityScore()
        return if (secondScore > firstScore) second else first
    }

    /** 候选下载优先级；分值越高越适合作为最终保存地址。 */
    private fun ImageCandidateData.downloadPriorityScore(): Int {
        val normalized = url.substringBefore("?").substringBefore("#").lowercase()
        // 按格式和站点变体累计优先级，不以到达顺序覆盖原图。
        var score = 0
        if (normalized.endsWith(".gif")) score += 100
        if (normalized.endsWith(".webp")) score += 40
        if (normalized.endsWith(".png")) score += 20
        if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) score += 10
        if (isStyledImageVariant()) score -= 30
        // 知乎常把页面展示地址写成 .jpg?source=...，服务端实际返回 WebP；如果同一 v2 图片还有 GIF 或原图变体，应该优先保存更接近原始内容的地址。
        score += zhihuImageDownloadPriorityBoost()
        return score
    }

    /**
     * 候选去重键。
     *
     * 通用处理 `xxx.gif@672w_...webp` 这类“原始图片 + 样式/转码后缀”URL；只有 `@` 前已经是明确图片文件时才归并到原图，
     * 避免把普通路径参数或查询签名误删。B 站 BFS 是主要触发来源，但规则本身不依赖具体链接。
     */
    private fun ImageCandidateData.dedupKey(): String {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        // 域名只用于识别特定 CDN 的变体约定。
        val host = uri?.host.orEmpty().lowercase()
        // 保留编码路径，避免破坏原始下载签名。
        val path = uri?.encodedPath.orEmpty()
        zhihuImageStableKey(uri, host, path)?.let { return it }
        // 仅明确的样式图路径才回退到原图身份。
        val normalizedPath = normalizedImageStylePath(path)
        return if (normalizedPath != null) {
            uri!!
                .buildUpon()
                .encodedPath(normalizedPath)
                .encodedQuery(uri.encodedQuery)
                .fragment(null)
                .build()
                .toString()
        } else {
            url.trim()
        }
    }

    /** 稳定候选 key；选择状态绑定它，最终下载 URL 可随候选优先级升级而变化。 */
    private fun ImageCandidateData.stableKey(): String {
        return dedupKey()
    }

    /**
     * 判断当前 URL 是否是样式转码变体，例如 `xxx.gif@672w_378h_1c.webp`。
     *
     * 只要满足 `@` 前是图片文件，就走相同优先级；这覆盖 B 站 BFS，也覆盖其他同类 CDN 样式图。
     */
    private fun ImageCandidateData.isStyledImageVariant(): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        // 域名只用于识别特定 CDN 的变体约定。
        val host = uri.host.orEmpty().lowercase()
        // 保留编码路径，避免破坏原始下载签名。
        val path = uri.encodedPath.orEmpty()
        return path.contains("@") && normalizedImageStylePath(path) != null ||
            host.endsWith("hdslb.com") && path.contains(BILI_BFS_PATH_MARKER) && path.contains("@")
    }

    /**
     * 知乎图片下载优先级修正。
     *
     * 同一 `v2-*` 资源可能同时出现 `.jpg?source=...` 转码地址、`.webp` 地址和 `.gif` 原图地址；已在预览里动起来的
     * `.jpg?source=...` 可能由服务端实际返回 Animated WebP，因此不能被普通 `.webp` 变体轻易覆盖，只对明确 GIF 做强升级。
     */
    private fun ImageCandidateData.zhihuImageDownloadPriorityBoost(): Int {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return 0
        // 域名只用于识别特定 CDN 的变体约定。
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith(ZHIHU_IMAGE_HOST_SUFFIX)) return 0
        // 解码路径只用于匹配资源身份，最终请求仍使用原 URL。
        val decodedPath = Uri.decode(uri.encodedPath.orEmpty()).lowercase()
        if (!decodedPath.contains("/v2-")) return 0
        // 按格式和站点变体累计优先级，不以到达顺序覆盖原图。
        var score = 0
        if (decodedPath.endsWith(".gif")) score += 80
        if (decodedPath.endsWith(".gif") && decodedPath.contains("_b.")) score += 25
        if (decodedPath.endsWith(".gif") && decodedPath.contains("_r.")) score += 12
        if (uri.getQueryParameter("source") != null && (decodedPath.endsWith(".jpg") || decodedPath.endsWith(".jpeg"))) {
            score += 30
        }
        return score
    }

    /**
     * 根据知乎 v2 图片标识生成稳定候选 key。
     *
     * `picx.zhimg.com/v2-abc.jpg?source=...`、`pic1.zhimg.com/v2-abc_b.gif` 等地址通常对应同一张内容的不同格式或尺寸，
     * 选择状态应该绑定资源标识而不是某个转码 URL，这样后续发现更优原图时不会丢失用户已经做出的选择。
     */
    private fun zhihuImageStableKey(uri: Uri?, host: String, encodedPath: String): String? {
        if (uri == null || !host.endsWith(ZHIHU_IMAGE_HOST_SUFFIX)) return null
        // 解码路径只用于匹配资源身份，最终请求仍使用原 URL。
        val decodedPath = Uri.decode(encodedPath)
        // 无法识别稳定资源标识时交回普通 URL 去重。
        val match = ZHIHU_V2_IMAGE_PATH_REGEX.find(decodedPath) ?: return null
        return "zhimg:${match.groupValues[1].lowercase()}"
    }

    /**
     * 从样式/转码 URL 路径中恢复原始图片路径。
     *
     * 只在 `@` 前缀本身已经以图片扩展名结束时返回原路径，例如 `/a/b/c.gif@672w.webp` -> `/a/b/c.gif`；
     * 普通 URL 或 `@` 前不是图片文件的路径保持不合并。
     */
    private fun normalizedImageStylePath(path: String): String? {
        if (!path.contains("@")) return null
        // 仅图片扩展名之前的路径可作为原图键。
        val rawPath = path.substringBefore("@")
        return rawPath.takeIf { IMAGE_FILE_PATH_REGEX.matches(Uri.decode(it)) }
    }

    /**
     * 合并已有元信息和新探测结果。
     *
     * 已有尺寸通常来自 DOM 或 Coil 解码，比请求头更可靠；新结果优先补充 MIME 和体积，避免探测失败时覆盖掉
     * UI 已经拿到的有效信息。
     */
    private fun mergeMeta(old: ImagePreviewMeta?, new: ImagePreviewMeta): ImagePreviewMeta {
        return ImagePreviewMeta(
            width = old?.width ?: new.width,
            height = old?.height ?: new.height,
            mimeType = new.mimeType ?: old?.mimeType,
            contentLength = new.contentLength ?: old?.contentLength,
            isLoading = false,
        )
    }

    /**
     * 根据 URL 后缀推断图片 MIME。
     *
     * 这是响应头探测失败时的兜底展示值，只覆盖常见图片格式；带查询参数或片段的 URL 会先去掉后缀噪声。
     */
    private fun guessMimeTypeFromUrl(url: String): String? {
        return when (url.substringBefore("?").substringBefore("#").substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> null
        }
    }

}
