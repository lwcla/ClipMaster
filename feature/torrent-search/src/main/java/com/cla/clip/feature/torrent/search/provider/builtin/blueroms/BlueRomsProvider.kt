package com.cla.clip.feature.torrent.search.provider.builtin.blueroms

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderParseException
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** 固定快照 BlueRoms search/details Provider；跨页网络留在调用协程，DOM/Base64 解析进入计算调度器。 */
internal class BlueRomsProvider(
    /** 功能内共享网络客户端，也是此 Provider 唯一网络入口。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** Jsoup、Base64 与领域映射使用的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider {
    /** BlueRoms 稳定能力、安全等级与默认关闭状态。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("blueroms"),
        displayName = "BlueRoms",
        baseUrl = BASE_URL,
        supportedCategories = setOf(Category.GAMES),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** manifest 审计通过的唯一精确 HTTPS host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("www.blueroms.ws"))
    /** 结果页只产生不含网络能力的 draft。 */
    private val resultsParser = BlueRomsResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情页只提取同站资源和下载页 draft。 */
    private val detailsParser = BlueRomsDetailsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 下载页 Base64 解析器不持有网络客户端。 */
    private val downloadParser = BlueRomsDownloadParser(parsingDispatcher)

    /** 构造固定参数顺序搜索 URL，先解析 draft，再按 DOM 顺序读取下载页。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 查询由 HttpUrl 编码且控制字符已删除。 */
        val requestUrl = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("g", "0")
            .addQueryParameter("p", "0")
            .addQueryParameter("q", sanitizeProviderQuery(query))
            .build()
            .toString()
        /** 结果页面网络读取保持调用协程。 */
        val responseHtml = networkClient.getText(requestUrl)
        /** DOM draft 在计算调度器完成，且已经校验所有输出 URL。 */
        val drafts = resultsParser.parse(responseHtml, requestUrl)
        /** 结果按 DOM 顺序累积，非法单条下载内容只跳过。 */
        val results = mutableListOf<Torrent>()
        /** draft 是当前待补全记录，每次网络前响应取消。 */
        for (draft in drafts) {
            currentCoroutineContext().ensureActive()
            /** 下载页 HTML 不跨记录缓存。 */
            val downloadHtml = networkClient.getText(draft.downloadPageUrl)
            /** Base64 与 magnet 校验都在计算调度器执行。 */
            val decodedMagnet = downloadParser.parse(downloadHtml) ?: continue
            /** 领域映射回到同一计算边界，避免调用线程处理文本。 */
            results += resultsParser.toTorrent(draft, decodedMagnet)
        }
        return results
    }

    /** 校验详情 host 后读取页面，再顺序读取同站下载页并映射完整详情。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 详情输入在网络前执行精确 host 校验。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 详情 HTML 只在内存中传递。 */
        val detailsHtml = networkClient.getText(trustedDetailsUrl)
        /** 详情 draft 包含已校验的下载页、海报与截图 URL。 */
        val detailsDraft = detailsParser.parseDraft(detailsHtml, trustedDetailsUrl) ?: return null
        currentCoroutineContext().ensureActive()
        /** 下载页请求保留在调用协程。 */
        val downloadHtml = networkClient.getText(detailsDraft.downloadPageUrl)
        /** 非法 Base64、UTF-8、scheme 或 btih 都使详情不可用。 */
        val decodedMagnet = downloadParser.parse(downloadHtml) ?: return null
        return detailsParser.toDetails(detailsDraft, decodedMagnet)
    }

    /** BlueRoms 固定网络常量。 */
    private companion object {
        /** manifest 审计通过的根地址。 */
        private const val BASE_URL = "https://www.blueroms.ws"
    }
}

/** BlueRoms 结果页的无网络解析器。 */
private class BlueRomsResultsParser(
    /** Torrent 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 详情与下载链接的精确 host 策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 按固定上源全局 selector 解析所有卡片；未匹配卡片按合法空结果返回。 */
    suspend fun parse(html: String, pageUrl: String): List<BlueRomsResultDraft> = withContext(parsingDispatcher) {
        /** Jsoup 静态解析不会执行站点脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** cards 保留所有同级 col-xs-12 下卡片的全局 DOM 顺序。 */
        val cards = document.select("div.row > div.col-xs-12 > div.card")
        /** card 是当前结果节点，逐条解析前检查取消。 */
        cards.mapNotNullCancellable { card -> parseCard(card, pageUrl) }
    }

    /** 将已补全 magnet 的 draft 映射为稳定 Torrent。 */
    suspend fun toTorrent(draft: BlueRomsResultDraft, decodedMagnet: DecodedMagnet): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = decodedMagnet.infoHash,
            name = draft.name,
            size = draft.size,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            category = Category.GAMES,
            descriptionPageUrl = draft.detailsPageUrl,
            magnetUri = decodedMagnet.uri,
        )
    }

    /** 解析单张游戏卡片；名称或下载页缺失时跳过。 */
    private fun parseCard(card: Element, pageUrl: String): BlueRomsResultDraft? {
        /** 游戏名称来自固定标题 anchor。 */
        val gameName = card.selectFirst("h4.card-title > a")?.ownText()?.takeIf(String::isNotBlank) ?: return null
        /** 平台文本为空时不追加分隔符。 */
        val platform = card.selectFirst("strong:containsOwn(Platform:)")?.nextElementSibling()?.ownText()?.takeIf(String::isNotBlank)
        /** 展示名称保留上源“名称 - 平台”语义。 */
        val torrentName = platform?.let { platformName -> "$gameName - $platformName" } ?: gameName
        /** 大小读取 Size: 后紧邻文本节点并仅插入缺失单位空格。 */
        val size = (card.selectFirst("strong:containsOwn(Size:)")?.nextSibling() as? TextNode)
            ?.nodeValue()
            ?.let(::normalizeProviderDisplaySize)
        /** 下载页是第二阶段必填网络地址。 */
        val downloadPageUrl = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            card.selectFirst("div.card-footer > a")?.attr("href"),
        ) ?: return null
        /** 详情页可空，但存在时必须同站 HTTPS。 */
        val detailsPageUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, card.selectFirst("h4.card-title > a")?.attr("href"))
        return BlueRomsResultDraft(torrentName, size, detailsPageUrl, downloadPageUrl)
    }
}

/** BlueRoms 详情页的无网络解析器。 */
private class BlueRomsDetailsParser(
    /** TorrentDetails 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 下载和图片地址的精确 host 策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 详情缺名称或下载页返回 null，资源链接存在时必须通过白名单。 */
    suspend fun parseDraft(html: String, pageUrl: String): BlueRomsDetailsDraft? = withContext(parsingDispatcher) {
        /** 静态详情 DOM 不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("main") == null && document.selectFirst("h3.custom-title") == null) throw ProviderParseException()
        /** 游戏名称是详情必填字段。 */
        val gameName = document.selectFirst("h3.custom-title")?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** 平台来自 label 父节点的自有文本。 */
        val platform = document.selectFirst("strong:containsOwn(Platform:)")?.parent()?.ownText()?.takeIf(String::isNotBlank)
        /** 展示名称保留平台后缀。 */
        val torrentName = platform?.let { platformName -> "$gameName - $platformName" } ?: gameName
        /** 大小来自 Files Size 父节点自有文本。 */
        val size = document.selectFirst("strong:containsOwn(Files Size:)")?.parent()?.ownText()?.let(::normalizeProviderDisplaySize)
        /** 下载页是详情第二阶段必填地址。 */
        val downloadPageUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, document.selectFirst("a[href^=/download/]")?.attr("href"))
            ?: return@withContext null
        /** 海报可空，存在时只允许同站 HTTPS。 */
        val posterUrl = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("img[src^=/static/game/]:not(.img-thumbnail)")?.attr("src"),
        )
        /** 截图按 DOM 顺序保留并逐个校验精确 host。 */
        val screenshotUrls = document
            .select("div.panel:has(div.panel-heading:has(h3:containsOwn(Screenshots))) img.img-thumbnail")
            /** image 是当前截图节点，输出前通过同站白名单。 */
            .mapNotNullCancellable { image -> urlPolicy.resolveOptionalHttpsUrl(pageUrl, image.attr("src")) }
        BlueRomsDetailsDraft(torrentName, size, downloadPageUrl, posterUrl, screenshotUrls)
    }

    /** 将详情 draft 与已校验 magnet 映射为领域详情。 */
    suspend fun toDetails(draft: BlueRomsDetailsDraft, decodedMagnet: DecodedMagnet): TorrentDetails = withContext(parsingDispatcher) {
        TorrentDetails(
            infoHash = decodedMagnet.infoHash,
            name = draft.name,
            size = draft.size,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            category = Category.GAMES,
            magnetUri = decodedMagnet.uri,
            posterUrl = draft.posterUrl,
            screenshotUrls = draft.screenshotUrls,
        )
    }
}

/** BlueRoms 下载页 Base64 与 btih 解析器。 */
private class BlueRomsDownloadParser(
    /** Base64、UTF-8 与 magnet 校验使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 非法 Base64、非法 UTF-8、空值、非 magnet 或非法 btih 都返回 null。 */
    suspend fun parse(html: String): DecodedMagnet? = withContext(parsingDispatcher) {
        /** 编码值只在此局部内存中存在，不记录或抛入异常正文。 */
        val encodedMagnet = Jsoup.parse(html).selectFirst("button#magnet-button[data-link]")
            ?.attr("data-link")
            ?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** Kotlin 标准库解码器保留标准 Base64 可选 padding，并避免引用 Android API 26 才提供的 java.util.Base64。 */
        val decodedBytes = runCatching {
            Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(encodedMagnet)
        }.getOrNull() ?: return@withContext null
        /** UTF-8 decoder 禁止用替换字符吞掉恶意无效字节。 */
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        /** 解码后的 URI 只用于 btih 校验和业务返回，不进入诊断。 */
        val magnetUri = runCatching { utf8Decoder.decode(ByteBuffer.wrap(decodedBytes)).toString() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** 规范化 infoHash 是书签和去重稳定键。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        DecodedMagnet(magnetUri, infoHash)
    }
}

/** BlueRoms 结果页第一阶段数据，不持有 DOM 或网络能力。 */
private data class BlueRomsResultDraft(
    /** 名称与可选平台组合后的展示文本。 */
    val name: String,
    /** 上源大小展示文本。 */
    val size: String?,
    /** 可选同站详情地址。 */
    val detailsPageUrl: String?,
    /** 必填同站下载页地址。 */
    val downloadPageUrl: String,
)

/** BlueRoms 详情页第一阶段数据。 */
private data class BlueRomsDetailsDraft(
    /** 详情名称与平台组合文本。 */
    val name: String,
    /** 详情大小展示文本。 */
    val size: String?,
    /** 必填同站下载页地址。 */
    val downloadPageUrl: String,
    /** 可选同站海报地址。 */
    val posterUrl: String?,
    /** 按 DOM 顺序保留的同站截图。 */
    val screenshotUrls: List<String>,
)

/** 已通过 scheme 与 btih 校验的解码结果。 */
private data class DecodedMagnet(
    /** 保留上源可下载 magnet URI。 */
    val uri: String,
    /** 规范化 40 位小写 infoHash。 */
    val infoHash: String,
)
