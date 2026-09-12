package com.cla.clip.feature.torrent.search.provider.builtin.audiobookbay

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 固定快照与当前站点共用的内容根选择器；只兼容已实证的 primary/content 两种容器。 */
private const val AUDIOBOOK_BAY_CONTENT_ROOT_SELECTOR = "#primary, #content"

/**
 * 固定快照 AudioBookBay search/details/latest Provider；结果 DOM 先解析 draft，
 * 再由调用协程按页面顺序读取详情 hash，避免把网络请求包进计算调度器。
 */
internal class AudioBookBayProvider(
    /** 功能内共享网络边界，Provider 不创建独立 HTTP 客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** Jsoup DOM 与领域映射使用的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, LatestTorrentsProvider, TorrentDetailsProvider {
    /** 固定上源 ID/能力与 manifest SAFE、默认关闭结论。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("audiobookbay"),
        displayName = "AudioBookBay",
        baseUrl = BASE_URL,
        supportedCategories = setOf(Category.BOOKS),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** 只允许 manifest 审计通过的 audiobookbay.lu 精确 host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("audiobookbay.lu"))
    /** 结果页解析器不持有网络能力，只产出待补 hash 的 draft。 */
    private val resultsParser = AudioBookBayResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** hash 页解析器只负责规范化 infoHash。 */
    private val hashParser = AudioBookBayHashParser(parsingDispatcher)
    /** 详情解析器只映射已下载 HTML。 */
    private val detailsParser = AudioBookBayDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 以编码后的 s 参数读取搜索页，再按 DOM 顺序补齐详情 hash。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 查询 URL 由 HttpUrl 负责百分号编码，控制字符先删除。 */
        val requestUrl = AudioBookBayUrlBuilder.buildSearchUrl(query)
        return loadResults(requestUrl)
    }

    /** latest 使用站点根地址，并复用与搜索相同的两阶段 hash 流程。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> = loadResults(BASE_URL_WITH_SLASH)

    /** 详情 URL 在网络前执行精确 HTTPS host 校验。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 已通过白名单的详情 URL，恶意输入不会进入网络 fake/客户端。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 原始 HTML 只在内存中传递，不进入异常或日志。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        return detailsParser.parse(responseHtml, trustedDetailsUrl)
    }

    /** 下载结果页、解析 draft，再逐条读取详情页 hash 并稳定保留原顺序。 */
    private suspend fun loadResults(requestUrl: String): List<Torrent> {
        urlPolicy.requireHttpsUrl(requestUrl)
        /** 结果 HTML 只在解析前短暂持有。 */
        val responseHtml = networkClient.getText(requestUrl)
        /** drafts 保留 DOM 顺序且已经完成所有详情 URL 白名单校验。 */
        val drafts = resultsParser.parse(responseHtml, requestUrl)
        /** draft 是当前 DOM 结果，按原顺序执行详情 hash 网络阶段。 */
        return drafts.mapNotNullCancellable { draft ->
            currentCoroutineContext().ensureActive()
            /** 当前详情 HTML 的网络请求仍在调用协程执行。 */
            val detailsHtml = networkClient.getText(draft.detailsPageUrl)
            /** 当前规范化 hash 在计算调度器解析；缺失或非法只跳过单条。 */
            val infoHash = hashParser.parse(detailsHtml) ?: return@mapNotNullCancellable null
            resultsParser.mapTorrent(draft, infoHash)
        }
    }

    /** AudioBookBay 固定快照地址。 */
    private companion object {
        /** Descriptor 使用的不带尾斜杠根地址。 */
        private const val BASE_URL = "https://audiobookbay.lu"
        /** latest 使用的规范根页面地址。 */
        private const val BASE_URL_WITH_SLASH = "https://audiobookbay.lu/"
    }
}

/** AudioBookBay URL 构造器，禁止字符串拼接用户查询。 */
private object AudioBookBayUrlBuilder {
    /** 构造稳定根路径和 s 参数搜索地址。 */
    fun buildSearchUrl(query: String): String = "https://audiobookbay.lu".toHttpUrl().newBuilder()
        .addQueryParameter("s", sanitizeProviderQuery(query))
        .build()
        .toString()
}

/** 结果页单条 draft；所有字段都来自已解析 DOM，不承载正文或网络客户端。 */
private data class AudioBookBayResultDraft(
    /** 已白名单校验的详情地址。 */
    val detailsPageUrl: String,
    /** 展示名称。 */
    val name: String,
    /** 上游展示大小。 */
    val size: String?,
    /** UTC 上传毫秒时间。 */
    val uploadEpochMillis: Long?,
)

/** AudioBookBay 结果 DOM parser；顶层缺失失败，单条缺字段跳过。 */
private class AudioBookBayResultsParser(
    /** Torrent 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 详情链接精确 host 校验器。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 和领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析结果页为无网络 draft；空 primary 容器表示合法空结果。 */
    suspend fun parse(html: String, pageUrl: String): List<AudioBookBayResultDraft> = withContext(parsingDispatcher) {
        /** Jsoup 只构建 DOM，不执行页面脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** resultsRoot 兼容固定快照 primary 与当前站点 content，二者都缺失才视为挑战或协议漂移。 */
        val resultsRoot = document.selectFirst(AUDIOBOOK_BAY_CONTENT_ROOT_SELECTOR) ?: throw ProviderParseException()
        /** post 是当前结果节点，缺必填字段时由 parseDraft 返回 null。 */
        resultsRoot.select("div.post").mapNotNullCancellable { post -> parseDraft(post, pageUrl) }
    }

    /** 将 draft 与规范化 hash 映射为 Torrent，继续保持计算调度器边界。 */
    suspend fun mapTorrent(draft: AudioBookBayResultDraft, infoHash: String): Torrent = withContext(parsingDispatcher) {
        currentCoroutineContext().ensureActive()
        Torrent(
            infoHash = infoHash,
            name = draft.name,
            size = draft.size,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            category = Category.BOOKS,
            descriptionPageUrl = draft.detailsPageUrl,
        )
    }

    /** 解析单条结果；标题或详情地址缺失时返回 null。 */
    private fun parseDraft(post: Element, pageUrl: String): AudioBookBayResultDraft? {
        /** 标题 anchor 同时承载必填名称和详情地址。 */
        val titleAnchor = post.selectFirst("div.postTitle > h2 > a") ?: return null
        /** 空白名称按缺失处理。 */
        val name = titleAnchor.ownText().takeIf(String::isNotBlank) ?: return null
        /** 详情相对地址解析后必须仍为精确允许 host。 */
        val detailsPageUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, titleAnchor.attr("href")) ?: return null
        /** 固定上源第三段内容含 File Size 与 Posted 行。 */
        val infoLines = post.selectFirst("div.postContent > p:nth-child(3)")?.wholeText()?.lines().orEmpty()
        /** 大小移除上源复数尾缀 s，无法识别时保留为空。 */
        val size = infoLines.firstOrNull { line -> line.trim().startsWith("File Size: ") }
            ?.substringAfter("File Size: ")
            ?.trim()
            ?.removeSuffix("s")
            ?.takeIf(String::isNotBlank)
        /** 上传日期按 d MMM yyyy 严格解析为 UTC 日起点。 */
        val uploadEpochMillis = infoLines.firstOrNull { line -> line.trim().startsWith("Posted: ") }
            ?.substringAfter("Posted: ")
            ?.trim()
            /** rawDate 是去除前缀后的站点日期，不允许尾部污染。 */
            ?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "d MMM yyyy") }
        return AudioBookBayResultDraft(detailsPageUrl, name, size, uploadEpochMillis)
    }
}

/** AudioBookBay hash 页解析器；非法或缺失值按单条缺字段返回 null。 */
private class AudioBookBayHashParser(
    /** Jsoup 与 hash 规范化使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 从 Info Hash 相邻单元格读取并规范化 40 hex/base32。 */
    suspend fun parse(html: String): String? = withContext(parsingDispatcher) {
        /** hash 页面 DOM 不执行任何脚本。 */
        val document = Jsoup.parse(html)
        /** 原始 hash 只在内存内短暂存在。 */
        val rawInfoHash = document.selectFirst("td:containsOwn(Info Hash:)")
            ?.nextElementSibling()
            ?.ownText()
            ?: return@withContext null
        MagnetInfoHashParser.normalize(rawInfoHash)
    }
}

/** AudioBookBay 详情解析器；缺名称/hash 返回 null，描述 HTML 只作为不可信展示字符串。 */
private class AudioBookBayDetailsParser(
    /** 详情领域模型的稳定 Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 海报地址使用的精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析完整详情；顶层 primary 缺失视为协议异常。 */
    suspend fun parse(html: String, pageUrl: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** Jsoup 只解析静态 DOM。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst(AUDIOBOOK_BAY_CONTENT_ROOT_SELECTOR) == null) throw ProviderParseException()
        /** 名称为空则详情不完整。 */
        val name = document.selectFirst("div.postTitle > h1")?.ownText()?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** 原始 hash 从固定相邻单元格读取。 */
        val rawInfoHash = document.selectFirst("td:containsOwn(Info Hash:)")
            ?.nextElementSibling()
            ?.ownText()
            ?: return@withContext null
        /** 规范化 hash 同时保护 magnet 主键。 */
        val infoHash = MagnetInfoHashParser.normalize(rawInfoHash) ?: return@withContext null
        /** 大小优先 File Size，缺失时回退 Combined File Size。 */
        val sizeCell = document.selectFirst("td:containsOwn(File Size:)")
            ?: document.selectFirst("td:containsOwn(Combined File Size:)")
        /** 移除固定复数尾缀，空值保持 null。 */
        val size = sizeCell?.nextElementSibling()?.text()?.trim()?.removeSuffix("s")?.takeIf(String::isNotBlank)
        /** RFC1123 创建时间按显式时区解析，非法值降级为空。 */
        val uploadEpochMillis = document.selectFirst("td:containsOwn(Creation Date:)")
            ?.nextElementSibling()
            ?.ownText()
            ?.let(ProviderDateParser::parseRfc1123EpochMillis)
        /** 上传者是可选展示字段。 */
        val uploader = document.selectFirst("div.postContent > div:nth-child(1) > p:nth-child(1) > a")
            ?.ownText()
            ?.takeIf(String::isNotBlank)
        /** 描述 HTML 保持不可信字符串，后续 UI 仍必须禁用脚本。 */
        val description = document.selectFirst("div.desc")?.html()?.takeIf(String::isNotBlank)
        /** 海报存在时解析相对地址并强制同 host HTTPS。 */
        val posterUrl = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("""img[itemprop="image"]""")?.attr("src"),
        )
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = size,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = Category.BOOKS,
            uploader = uploader,
            magnetUri = MagnetInfoHashParser.create(infoHash),
            description = description,
            posterUrl = posterUrl,
        )
    }
}
