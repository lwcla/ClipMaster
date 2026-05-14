package com.cla.clip.master.ui.page.image

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cla.clip.base.general.dao.ImageExtractBatchData
import com.cla.clip.base.general.dao.ImageExtractItemData
import com.cla.clip.base.general.repository.ImageCandidateData
import com.cla.clip.base.general.repository.ImageExtractRepository
import com.cla.clip.base.general.utils.logD
import com.cla.clip.base.general.utils.logE
import com.cla.clip.master.utils.WebViewLinkPreviewExtractor
import com.cla.clip.master.work.DownloadImagesWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Provider
import javax.inject.Inject

/**
 * 图片提取页状态。
 *
 * Probing 表示 WebView 仍在加载和扫描网页，Extracted 表示候选已经落库并等待用户筛选确认，
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
     * 图片候选已经落库，UI 可以根据批次 id 观察候选列表并让用户筛选。
     *
     * `count` 是落库时的候选总数，用于提取完成的首屏反馈；后续用户取消选择不会回写这个状态对象。
     */
    data class Extracted(
        /** 当前图片提取批次 id，必须对应 `image_extract_batches` 中已存在的记录，UI 会用它继续观察图片项。 */
        val batchId: Long,

        /** 本次落库的候选图片数量，取值不小于 0，仅用于提取完成提示，不代表用户最终选择下载的数量。 */
        val count: Int
    ) : ImageProbeState

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
    private val imageExtractRepo: ImageExtractRepository,
    private val okHttpClient: Provider<OkHttpClient>,
    private val linkPreviewExtractor: WebViewLinkPreviewExtractor,
) : ViewModel() {

    companion object {
        /** 日志标签，仅用于图片提取 ViewModel 内部调试，不展示给用户。 */
        private const val TAG = "ImageExtractVm"
    }

    /** 当前图片提取页面状态，驱动 UI 在探测中、已提取、失败重试之间切换。 */
    var probeState by mutableStateOf<ImageProbeState>(ImageProbeState.Idle)

    /** 当前探测会话编号，递增后会触发页面重新加载 WebView 并丢弃旧会话的超时结果。 */
    var sessionId by mutableIntStateOf(0)

    /** WebView 网络层捕获到的图片候选，用 URL 去重后作为 DOM 扫描遗漏图片的补充来源。 */
    private val networkCandidates = linkedMapOf<String, ImageCandidateData>()

    /**
     * 当前探测会话内已经发现的阶段性候选。
     *
     * 只用于加载页进度、阶段性图片查看和手动结束提取，不直接写入 Room；正式批次仍在自动完成或用户手动结束时统一创建。
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

    /** 当前页面内的图片元信息缓存，避免底部预览反复探测同一张图片。 */
    val previewMetaCache = mutableStateMapOf<Long, ImagePreviewMeta>()

    /**
     * 新一轮 WebView 探测开始前清空旧候选。
     *
     * 重试时必须丢弃上一轮网络请求和阶段性候选，避免动态页面的迟到请求混入新批次。
     */
    fun resetProbeSession(newSessionId: Int) {
        synchronized(probingCandidatesLock) {
            activeCandidateSessionId = newSessionId
            networkCandidates.clear()
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
            if (!networkCandidates.containsKey(candidate.url)) {
                networkCandidates[candidate.url] = candidate.copy(displayOrder = Int.MAX_VALUE - networkCandidates.size)
            }
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
            probingCandidateMap.values.sortedBy { it.displayOrder }.toList()
        }
    }

    /** 将线程安全候选快照同步给 Compose 状态，只能在主线程调用。 */
    fun publishProbingCandidates(expectedSessionId: Int, candidates: List<ImageCandidateData>) {
        if (!isCandidateSessionActive(expectedSessionId)) return
        probingCandidates.clear()
        probingCandidates.addAll(candidates.sortedBy { it.displayOrder })
    }

    /** 返回当前阶段性候选快照，供手动结束或失败兜底判断使用。 */
    fun snapshotProbingCandidates(): List<ImageCandidateData> {
        return synchronized(probingCandidatesLock) {
            probingCandidateMap.values.sortedBy { it.displayOrder }.toList()
        }
    }

    /**
     * 自动完成或手动结束时保存最终候选。
     *
     * 保存前校验 session 和状态，避免旧协程在超时、重试或页面退出后迟到写库并覆盖新页面状态。
     */
    fun saveExtractedImagesIfActive(
        expectedSessionId: Int,
        pageUrl: String,
        pageName: String,
        domCandidates: List<ImageCandidateData>,
    ) {
        if (probeState != ImageProbeState.Probing(expectedSessionId)) return
        closeProbeSession(expectedSessionId)
        saveExtractedImages(expectedSessionId, pageUrl, pageName, domCandidates)
    }

    /** 当前探测确认失败时切换失败态，旧会话迟到调用会被 session 校验丢弃。 */
    fun failProbeIfActive(expectedSessionId: Int) {
        if (probeState == ImageProbeState.Probing(expectedSessionId)) {
            closeProbeSession(expectedSessionId)
            probeState = ImageProbeState.Failed
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
        linkPreviewExtractor.extractAndSave(webView, pageUrl, fallbackImageUrl)
    }

    /** 保存提取结果到数据库，页面退出后 Worker 仍能读取完整候选列表。 */
    private fun saveExtractedImages(expectedSessionId: Int, pageUrl: String, pageName: String, domCandidates: List<ImageCandidateData>) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val merged = mergeCandidates(domCandidates)
                if (merged.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        failProbeIfActive(expectedSessionId)
                    }
                    return@launch
                }
                val batchId = imageExtractRepo.createBatch(pageUrl, pageName, merged)
                logD(TAG) { "saveExtractedImages: batchId=$batchId count=${merged.size}" }
                withContext(Dispatchers.Main) {
                    if (probeState == ImageProbeState.Probing(expectedSessionId)) {
                        probeState = ImageProbeState.Extracted(batchId, merged.size)
                    }
                }
            }.onFailure {
                logE(TAG, it) { "saveExtractedImages: 保存图片候选失败" }
                withContext(Dispatchers.Main) {
                    failProbeIfActive(expectedSessionId)
                }
            }
        }
    }

    /**
     * 用户确认选择后才启动 Worker。
     *
     * 这里会先删除未选中的候选并更新批次总数，确保下载进度、结果统计和用户真正选择的图片数量一致。
     */
    fun startDownload(batchId: Long, selectedItemIds: Set<Long>) {
        if (selectedItemIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                imageExtractRepo.keepSelectedItems(batchId, selectedItemIds)
                DownloadImagesWorker.enqueue(appContext, batchId)
            }.onFailure {
                logE(TAG, it) { "startDownload: 确认图片选择失败" }
            }
        }
    }

    /** 观察批量任务状态，下载完成后展示成功/失败数量。 */
    fun observeBatch(batchId: Long): Flow<ImageExtractBatchData?> {
        return imageExtractRepo.observeBatch(batchId)
    }

    /** 观察本批次图片候选，UI 用它展示可勾选的缩略图列表。 */
    fun observeItems(batchId: Long): Flow<List<ImageExtractItemData>> {
        return imageExtractRepo.observeItems(batchId)
    }

    /** Coil 解码完成后回填尺寸，仅作为本次页面预览状态，不写入数据库。 */
    fun updateDecodedSize(itemId: Long, width: Int?, height: Int?) {
        if (width == null || height == null || width <= 0 || height <= 0) return
        val old = previewMetaCache[itemId] ?: ImagePreviewMeta()
        if (old.width == width && old.height == height) return
        previewMetaCache[itemId] = old.copy(width = width, height = height)
    }

    /**
     * 尽力探测图片响应头。
     *
     * HEAD 不可靠时降级到 Range GET，只读取服务端返回的头信息；如果仍然拿不到体积则保持未知，避免预览阶段提前下载完整文件。
     */
    fun loadPreviewMeta(item: ImageExtractItemData) {
        val cached = previewMetaCache[item.id]
        if (cached?.isLoading == true || cached?.mimeType != null && cached.contentLength != null) {
            return
        }

        previewMetaCache[item.id] = (cached ?: ImagePreviewMeta()).copy(
            width = cached?.width ?: item.width,
            height = cached?.height ?: item.height,
            mimeType = cached?.mimeType ?: guessMimeTypeFromUrl(item.url),
            isLoading = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val meta = runCatching { requestPreviewMeta(item) }
                .getOrElse {
                    logD(TAG) { "loadPreviewMeta: 探测图片元信息失败: ${it.message}" }
                    ImagePreviewMeta(
                        width = item.width,
                        height = item.height,
                        mimeType = guessMimeTypeFromUrl(item.url),
                    )
                }
            previewMetaCache[item.id] = mergeMeta(previewMetaCache[item.id], meta).copy(isLoading = false)
        }
    }

    /**
     * 合并 DOM 扫描和网络拦截得到的图片候选。
     *
     * DOM 候选保留网页展示顺序，网络候选只补充 DOM 没发现的地址；这样既能覆盖懒加载或脚本动态请求的图片，
     * 又不会让异步请求先后顺序打乱用户看到的图片排列和后续文件命名。
     */
    private fun mergeCandidates(domCandidates: List<ImageCandidateData>): List<ImageCandidateData> {
        val result = linkedMapOf<String, ImageCandidateData>()
        domCandidates.forEachIndexed { index, candidate ->
            val url = candidate.url.trim()
            if (url.isNotBlank() && !result.containsKey(url)) {
                result[url] = candidate.copy(displayOrder = index)
            }
        }
        synchronized(probingCandidatesLock) {
            networkCandidates.values.forEach { candidate ->
                val url = candidate.url.trim()
                if (url.isNotBlank() && !result.containsKey(url)) {
                    // 网络补充图排在 DOM 图片后面，确保用户看到的页面顺序优先。
                    result[url] = candidate.copy(displayOrder = result.size)
                }
            }
        }
        return result.values.toList()
    }

    /**
     * 合并单个阶段性候选。
     *
     * DOM 候选优先保留网页顺序和尺寸；网络候选补充 Referer、User-Agent、Cookie 等下载上下文。
     */
    private fun mergeProbingCandidateLocked(candidate: ImageCandidateData) {
        val url = candidate.url.trim()
        if (url.isBlank()) return
        val old = probingCandidateMap[url]
        probingCandidateMap[url] = if (old == null) {
            candidate
        } else {
            old.copy(
                referer = old.referer ?: candidate.referer,
                userAgent = old.userAgent ?: candidate.userAgent,
                cookie = old.cookie ?: candidate.cookie,
                displayOrder = minOf(old.displayOrder, candidate.displayOrder),
                width = old.width ?: candidate.width,
                height = old.height ?: candidate.height,
            )
        }
    }

    /**
     * 请求单张图片的预览元信息。
     *
     * 先尝试 HEAD 获取 Content-Type 和 Content-Length；部分站点禁用 HEAD 或不返回长度时，再用 Range GET
     * 只请求第一个字节以读取响应头。这里不写数据库，失败也只影响预览弹窗里的类型/体积展示。
     */
    private fun requestPreviewMeta(item: ImageExtractItemData): ImagePreviewMeta {
        val head = executeMetaRequest(item, "HEAD")
        val response = if (head != null && (head.contentType != null || head.contentLength != null)) {
            head
        } else {
            executeMetaRequest(item, "GET")
        }
        return ImagePreviewMeta(
            width = item.width,
            height = item.height,
            mimeType = response?.contentType ?: guessMimeTypeFromUrl(item.url),
            contentLength = response?.contentLength,
        )
    }

    /**
     * 执行图片响应头探测请求。
     *
     * GET 模式会加 `Range: bytes=0-0`，目的是尽量只让服务端返回极小内容；请求会携带图片项记录的
     * Referer、User-Agent 和 Cookie，保持预览探测与真实下载处在相近的反盗链上下文中。
     */
    private fun executeMetaRequest(item: ImageExtractItemData, method: String): HeaderMeta? {
        val referer = item.referer
        val userAgent = item.userAgent
        val cookie = item.cookie
        val request = Request.Builder()
            .url(item.url)
            .method(method, null)
            .apply {
                if (method == "GET") header("Range", "bytes=0-0")
                if (!referer.isNullOrBlank()) header("Referer", referer)
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            }
            .build()
        return okHttpClient.get().newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return@use null
            HeaderMeta(
                contentType = response.header("Content-Type")?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() },
                contentLength = parseContentLength(response.header("Content-Length"), response.header("Content-Range")),
            )
        }
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
     * 从响应头解析图片体积。
     *
     * Range 请求成功时优先使用 `Content-Range` 的总长度，因为 `Content-Length` 可能只是当前 1 字节响应体长度；
     * 普通 HEAD 或完整响应才退回读取 `Content-Length`。
     */
    private fun parseContentLength(contentLength: String?, contentRange: String?): Long? {
        val rangeTotal = contentRange
            ?.substringAfter("/", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()
        return rangeTotal ?: contentLength?.toLongOrNull()
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

    /**
     * 图片响应头探测结果。
     *
     * `contentType` 是去掉 charset 等参数后的 MIME；`contentLength` 表示整张图片体积，可能因为服务端不返回长度而为空。
     */
    private data class HeaderMeta(
        /** 响应头中的图片 MIME，已经去掉 `; charset=...` 等参数；为空表示响应头没有可用类型。 */
        val contentType: String?,

        /** 响应头推导出的完整图片体积，单位为字节；为空表示服务端没有暴露可用长度。 */
        val contentLength: Long?,
    )
}
