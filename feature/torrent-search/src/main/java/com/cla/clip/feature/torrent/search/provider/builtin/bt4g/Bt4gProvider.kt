package com.cla.clip.feature.torrent.search.provider.builtin.bt4g

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
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.normalizeProviderDisplaySize
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 固定快照 BT4G search/details/latest/top Provider；详情补 hash 按 DOM 顺序在调用协程联网。 */
internal class Bt4gProvider(
    /** 功能内共享网络客户端，也是唯一网络能力。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期使用的可注入当前毫秒。 */
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** DOM、hash、日期和领域映射使用的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** BT4G 稳定能力、Cloudflare 元数据与默认关闭状态。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("bt4g"),
        displayName = "BT4G",
        baseUrl = BASE_URL,
        cloudflareSolverUrl = "$BASE_URL/search?q=ubuntu",
        supportedCategories = setOf(Category.APPS, Category.BOOKS, Category.MOVIES, Category.MUSIC, Category.OTHER),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
        isCloudflareProtected = true,
    )
    /** manifest 审计通过的唯一请求 host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("bt4gprx.com"))
    /** 结果页 parser 只产生无网络 draft。 */
    private val resultsParser = Bt4gResultsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 详情 parser 只处理同站 HTML 与固定外域 marker 字符串。 */
    private val detailsParser = Bt4gDetailsParser(descriptor, currentTimeMillis, parsingDispatcher)

    /** 按分类构造固定 URL 并执行两阶段搜索。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = loadResults(
        Bt4gUrlBuilder.buildSearchUrl(query, category),
    )

    /** latest 使用固定 `/new`，分类参数不被上源支持。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> = loadResults("$BASE_URL/new")

    /** top 使用固定 `/week`，分类参数不被上源支持。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> = loadResults("$BASE_URL/week")

    /** 校验详情 host 后读取并解析完整详情。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 网络前校验的精确同站详情地址。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 详情页面读取保持调用协程。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        return detailsParser.parseDetails(responseHtml)
    }

    /** 读取结果页、解析 draft，再按顺序访问每条同站详情页补 infoHash。 */
    private suspend fun loadResults(requestUrl: String): List<Torrent> {
        /** 固定 browse URL 也通过相同白名单，防止内部常量漂移。 */
        val trustedRequestUrl = urlPolicy.requireHttpsUrl(requestUrl).toString()
        /** 结果页读取保持调用协程。 */
        val responseHtml = networkClient.getText(trustedRequestUrl)
        /** DOM draft 在计算调度器解析。 */
        val drafts = resultsParser.parse(responseHtml, trustedRequestUrl)
        /** 结果按 DOM 顺序累积，缺 marker 的单条跳过。 */
        val results = mutableListOf<Torrent>()
        /** draft 是当前待补 hash 的结果，每次请求前响应取消。 */
        for (draft in drafts) {
            currentCoroutineContext().ensureActive()
            /** 只请求已经过 bt4gprx.com 白名单的详情页。 */
            val detailsHtml = networkClient.getText(draft.detailsPageUrl)
            /** 固定外域 marker 仅在内存离线提 hash，绝不进入网络客户端。 */
            val infoHash = detailsParser.extractInfoHash(detailsHtml) ?: continue
            /** 领域映射在计算调度器执行。 */
            results += resultsParser.toTorrent(draft, infoHash)
        }
        return results
    }

    /** BT4G 固定网络常量。 */
    private companion object {
        /** manifest 审计通过的根地址。 */
        private const val BASE_URL = "https://bt4gprx.com"
    }
}

/** BT4G URL builder；用户查询和分类值均交由 HttpUrl 编码。 */
private object Bt4gUrlBuilder {
    /** 构造 q/category/orderby/p 固定参数顺序的搜索 URL。 */
    fun buildSearchUrl(query: String, category: Category): String = "https://bt4gprx.com".toHttpUrl().newBuilder()
        .addPathSegment("search")
        .addQueryParameter("q", sanitizeProviderQuery(query))
        .addQueryParameter("category", mapRequestCategory(category))
        .addQueryParameter("orderby", "seeders")
        .addQueryParameter("p", "1")
        .build()
        .toString()

    /** 固定映射站点分类；ALL 和不支持分类都回退 all。 */
    private fun mapRequestCategory(category: Category): String = when (category) {
        Category.APPS -> "app"
        Category.BOOKS -> "doc"
        Category.MOVIES -> "movie"
        Category.MUSIC -> "audio"
        Category.OTHER -> "other"
        else -> "all"
    }
}

/** BT4G 结果页无网络解析器。 */
private class Bt4gResultsParser(
    /** Torrent 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 详情 URL 精确 host 策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 相对日期注入时刻。 */
    private val currentTimeMillis: () -> Long,
    /** DOM、日期和领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 缺 main 顶层容器抛低敏异常，合法空 main 返回空 draft。 */
    suspend fun parse(html: String, pageUrl: String): List<Bt4gResultDraft> = withContext(parsingDispatcher) {
        /** Jsoup 静态解析不执行站点脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("main") == null) throw ProviderParseException()
        /** item 是当前上源结果节点，逐条解析前检查取消。 */
        document.select("div.notion-list-item").mapNotNullCancellable { item -> parseItem(item, pageUrl) }
    }

    /** 将已离线提取的 hash 与 draft 映射为 Torrent；不产出 marker URL 或 magnet。 */
    suspend fun toTorrent(draft: Bt4gResultDraft, infoHash: String): Torrent = withContext(parsingDispatcher) {
        Torrent(
            infoHash = infoHash,
            name = draft.name,
            size = draft.size,
            seeders = draft.seeders,
            peers = draft.peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = draft.uploadEpochMillis,
            category = draft.category,
            descriptionPageUrl = draft.detailsPageUrl,
        )
    }

    /** 解析单条结果；名称或同站详情 URL 缺失时跳过。 */
    private fun parseItem(item: Element, pageUrl: String): Bt4gResultDraft? {
        /** 名称来自固定标题 anchor。 */
        val nameAnchor = item.selectFirst("div.notion-list-item-title > a") ?: return null
        /** 空标题不可形成可见结果。 */
        val name = nameAnchor.text().takeIf(String::isNotBlank) ?: return null
        /** 详情页是第二阶段必填地址且必须同站 HTTPS。 */
        val detailsPageUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, nameAnchor.attr("href")) ?: return null
        /** 大小只规范紧邻单位的空格。 */
        val size = item.selectFirst("div.notion-list-item-meta > span:nth-child(5) > b")
            ?.ownText()
            ?.let(::normalizeProviderDisplaySize)
        /** 做种数非法时保持 null。 */
        val seeders = item.selectFirst("div.notion-list-item-meta span#seeders")?.ownText()?.toUIntOrNull()
        /** 下载人数非法时保持 null。 */
        val peers = item.selectFirst("div.notion-list-item-meta span#leechers")?.ownText()?.toUIntOrNull()
        /** Creation Time 同时支持固定 UTC 日期和注入时钟相对日期。 */
        val uploadEpochMillis = item.selectFirst("div.notion-list-item-meta > span:nth-child(2)")
            ?.ownText()
            ?.removePrefix("Creation Time: ")
            ?.trim()
            /** rawDate 是结果页固定或相对创建日期。 */
            ?.let { rawDate -> parseBt4gDate(rawDate, currentTimeMillis()) }
        /** 未知类别回退 OTHER。 */
        val category = mapBt4gCategory(item.selectFirst("div.notion-list-item-meta span.notion-tag")?.ownText().orEmpty())
        return Bt4gResultDraft(name, size, seeders, peers, uploadEpochMillis, category, detailsPageUrl)
    }
}

/** BT4G 详情页与固定外域 marker 的无网络解析器。 */
private class Bt4gDetailsParser(
    /** TorrentDetails 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 相对日期注入时刻。 */
    private val currentTimeMillis: () -> Long,
    /** DOM、hash、日期和领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 从页面离线提取精确 marker 中的 hash；任何其他 host/path/port 都返回 null。 */
    suspend fun extractInfoHash(html: String): String? = withContext(parsingDispatcher) {
        /** 文档只在当前计算任务内存在。 */
        val document = Jsoup.parse(html)
        extractMarkerInfoHash(document)
    }

    /** 解析完整详情；缺顶层 main 抛异常，缺名称或合法 marker 返回 null。 */
    suspend fun parseDetails(html: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** 详情 DOM 不执行脚本。 */
        val document = Jsoup.parse(html)
        if (document.selectFirst("main") == null) throw ProviderParseException()
        /** 固定外域 marker 仅产生规范 hash。 */
        val infoHash = extractMarkerInfoHash(document) ?: return@withContext null
        /** 名称是详情必填字段。 */
        val name = document.selectFirst("h1.notion-detail-title")?.ownText()?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** 大小读取 label 的下一兄弟并插入必要空格。 */
        val size = propertyText(document, "File Size")?.let(::normalizeProviderDisplaySize)
        /** 做种数非法时保持 null。 */
        val seeders = document.selectFirst("span#seeders")?.ownText()?.toUIntOrNull()
        /** 下载人数非法时保持 null。 */
        val peers = document.selectFirst("span#leechers")?.ownText()?.toUIntOrNull()
        /** 上传日期同时接受固定日期与相对表达。 */
        val uploadEpochMillis = propertyText(document, "Creation Time")?.let { rawDate ->
            parseBt4gDate(rawDate, currentTimeMillis())
        }
        /** 类别未知时回退 OTHER。 */
        val category = mapBt4gCategory(propertyText(document, "File Type").orEmpty())
        /** 最后检查时间按相对或固定日期解析。 */
        val lastCheckedEpochMillis = propertyText(document, "Updated")?.let { rawDate ->
            parseBt4gDate(rawDate, currentTimeMillis())
        }
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = size,
            seeders = seeders,
            peers = peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            lastCheckedEpochMillis = lastCheckedEpochMillis,
            magnetUri = MagnetInfoHashParser.create(infoHash),
        )
    }

    /** 从已解析文档中匹配唯一允许的 scheme-relative marker。 */
    private fun extractMarkerInfoHash(document: Document): String? {
        /** href 必须字面以双斜杠开始，绝不接受 http/https 或其他协议。 */
        val markerHref = document.select("a[href]")
            .asSequence()
            /** anchor 是当前链接节点，只提取原始 href 做离线匹配。 */
            .map { anchor -> anchor.attr("href") }
            /** href 是当前候选，仅允许字面固定 marker 前缀。 */
            .firstOrNull { href -> href.startsWith("//downloadtorrentfile.com/hash/") }
            ?: return null
        /** 仅为离线结构校验补上 HTTPS，不把该地址交给网络层或模型。 */
        val parsedMarker = "https:$markerHref".toHttpUrlOrNull() ?: return null
        if (parsedMarker.host != "downloadtorrentfile.com" || parsedMarker.port != 443 || parsedMarker.username.isNotEmpty() || parsedMarker.password.isNotEmpty()) {
            return null
        }
        /** 路径必须恰好由 hash 固定前缀和一个 hash segment 构成。 */
        val pathSegments = parsedMarker.pathSegments
        if (pathSegments.size != 2 || pathSegments[0] != "hash") return null
        return MagnetInfoHashParser.normalize(pathSegments[1])
    }

    /** 按固定 label 读取相邻属性值。 */
    private fun propertyText(document: Document, label: String): String? = document
        .selectFirst("span.notion-property-label:containsOwn($label)")
        ?.nextElementSibling()
        ?.ownText()
        ?.takeIf(String::isNotBlank)
}

/** BT4G 结果页第一阶段数据，不持有 DOM 或 marker URL。 */
private data class Bt4gResultDraft(
    /** Torrent 展示名称。 */
    val name: String,
    /** 上源大小展示文本。 */
    val size: String?,
    /** 可选做种数。 */
    val seeders: UInt?,
    /** 可选下载人数。 */
    val peers: UInt?,
    /** 可选 UTC 上传毫秒。 */
    val uploadEpochMillis: Long?,
    /** 上源类别的稳定映射。 */
    val category: Category,
    /** 第二阶段唯一允许访问的同站详情地址。 */
    val detailsPageUrl: String,
)

/** BT4G 日期先尝试严格 yyyy-MM-dd，再按注入时刻解析相对表达。 */
private fun parseBt4gDate(rawDate: String, currentEpochMillis: Long): Long? =
    ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy-MM-dd")
        ?: ProviderDateParser.parseRelativeEpochMillis(rawDate, currentEpochMillis)

/** 固定映射 BT4G 类别文本，未知值不依赖枚举名称并回退 OTHER。 */
private fun mapBt4gCategory(rawCategory: String): Category = when (rawCategory.trim()) {
    "Video", "Movie" -> Category.MOVIES
    "Audio" -> Category.MUSIC
    "Doc" -> Category.BOOKS
    "App", "Application" -> Category.APPS
    else -> Category.OTHER
}
