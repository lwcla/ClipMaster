package com.cla.clip.feature.torrent.search.provider.builtin.bitsearch

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
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 固定快照 BitSearch search/details/latest/top Provider；搜索固定并发 1..5 页，
 * 网络任务继承调用协程上下文，DOM/领域映射切到可注入计算调度器。
 */
internal class BitSearchProvider(
    /** 功能内共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期解析使用的可注入当前毫秒。 */
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** Jsoup DOM 和逐条领域映射使用的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** BitSearch 稳定能力与本地 manifest NSFW、默认关闭审计结论。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("bitsearch"),
        displayName = "BitSearch",
        baseUrl = BASE_URL,
        supportedCategories = setOf(
            Category.ANIME,
            Category.APPS,
            Category.BOOKS,
            Category.GAMES,
            Category.MOVIES,
            Category.MUSIC,
            Category.PORN,
            Category.SERIES,
            Category.OTHER,
        ),
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
    )
    /** manifest 只允许 bitsearch.to 精确 host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("bitsearch.to"))
    /** 结果 DOM parser 不持有网络能力。 */
    private val resultsParser = BitSearchResultsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 详情 DOM parser 不持有网络能力。 */
    private val detailsParser = BitSearchDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 立即发起固定五页请求，并按 page Deferred 建立顺序稳定 flatten。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = coroutineScope {
        /** 五个 Deferred 按页码升序保存，awaitAll 结果顺序与此列表一致。 */
        val pageTasks = SEARCH_PAGES.map { page ->
            async {
                /** 每页 URL 由 HttpUrl 以稳定参数顺序构造。 */
                val requestUrl = BitSearchUrlBuilder.buildSearchUrl(query, page, category)
                loadResults(requestUrl)
            }
        }
        pageTasks.awaitAll().flatten()
    }

    /** latest 使用固定路径和可选分类数字参数。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> =
        loadResults(BitSearchUrlBuilder.buildBrowseUrl("latest", category))

    /** top 使用固定 trending 路径和可选分类数字参数。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> =
        loadResults(BitSearchUrlBuilder.buildBrowseUrl("trending", category))

    /** 详情输入先校验精确 host，再下载和解析 DOM。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 经白名单校验的详情 URL。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 详情 HTML 不进入日志或异常。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        return detailsParser.parse(responseHtml, trustedDetailsUrl)
    }

    /** 读取单页 HTML 并进入计算 parser。 */
    private suspend fun loadResults(requestUrl: String): List<Torrent> {
        urlPolicy.requireHttpsUrl(requestUrl)
        /** 单页响应只在内存中传递。 */
        val responseHtml = networkClient.getText(requestUrl)
        return resultsParser.parse(responseHtml, requestUrl)
    }

    /** BitSearch 固定网络常量。 */
    private companion object {
        /** manifest 审计通过的唯一 HTTPS 根地址。 */
        private const val BASE_URL = "https://bitsearch.to"
        /** 固定上源搜索页码，禁止运行时扩大。 */
        private val SEARCH_PAGES = 1..5
    }
}

/** BitSearch URL builder；查询、页码和分类均由 HttpUrl 编码。 */
private object BitSearchUrlBuilder {
    /** 构造 q/page/sortBy/可选 category 参数顺序稳定的搜索 URL。 */
    fun buildSearchUrl(query: String, page: Int, category: Category): String {
        /** builder 从固定 HTTPS 根地址派生。 */
        val builder = "https://bitsearch.to".toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", sanitizeProviderQuery(query))
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sortBy", "seeders")
        /** ALL 不传 category，其余使用固定数字协议。 */
        bitSearchCategoryId(category)?.let { categoryId -> builder.addQueryParameter("category", categoryId.toString()) }
        return builder.build().toString()
    }

    /** 构造 latest/trending URL；path 只由内部常量调用。 */
    fun buildBrowseUrl(path: String, category: Category): String {
        /** browse builder 不接收用户路径。 */
        val builder = "https://bitsearch.to".toHttpUrl().newBuilder().addPathSegment(path)
        /** ALL 不限定类别。 */
        bitSearchCategoryId(category)?.let { categoryId -> builder.addQueryParameter("category", categoryId.toString()) }
        return builder.build().toString()
    }
}

/** 将稳定 Category 映射为 BitSearch 固定数字；ALL 不发送参数。 */
private fun bitSearchCategoryId(category: Category): Int? = when (category) {
    Category.ALL -> null
    Category.ANIME -> 4
    Category.APPS -> 5
    Category.BOOKS -> 9
    Category.GAMES -> 6
    Category.MOVIES -> 2
    Category.MUSIC -> 7
    Category.PORN -> 10
    Category.SERIES -> 3
    Category.OTHER -> 1
}

/** BitSearch 结果 DOM parser；兼容固定上源结构与等价最小合成 data-field fixture。 */
private class BitSearchResultsParser(
    /** Torrent 稳定 Provider 字段。 */
    private val descriptor: ProviderDescriptor,
    /** 详情和 torrent 下载链接白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 相对日期解析的注入时刻。 */
    private val currentTimeMillis: () -> Long,
    /** DOM 与领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 顶层 `.space-y-4` 存在且零条目为合法空结果。 */
    suspend fun parse(html: String, pageUrl: String): List<Torrent> = withContext(parsingDispatcher) {
        /** Jsoup 静态 DOM 不执行 script。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 固定结果容器缺失代表协议漂移。 */
        val container = document.selectFirst("div.space-y-4") ?: throw ProviderParseException()
        /** 合成 fixture 使用语义 article，真实快照使用固定嵌套 div。 */
        val entries = container.select("article[data-torrent-result]").ifEmpty {
            /** 固定上源结构从 document 根选择，避免旧 Jsoup 不支持 Element 上的 :scope。 */
            document.select("div.space-y-4 > div > div:nth-child(1)")
        }
        /** entry 是当前结果节点，逐条解析前响应取消。 */
        entries.mapNotNullCancellable { entry -> parseEntry(entry, pageUrl) }
    }

    /** 解析单条结果；名称/magnet/btih 缺失跳过，恶意外链抛 host 拒绝。 */
    private fun parseEntry(entry: Element, pageUrl: String): Torrent? {
        /** 名称优先语义 fixture，再回退固定上源 h3。 */
        val nameElement = entry.selectFirst("[data-field=name]") ?: entry.selectFirst("div:nth-child(1) h3")
        /** 结果名称必填。 */
        val name = nameElement?.text()?.takeIf(String::isNotBlank) ?: return null
        /** magnet anchor 优先语义 fixture，再回退第二下载按钮。 */
        val magnetUri = (entry.selectFirst("a[data-field=magnet]")
            ?: entry.selectFirst("> div:nth-child(2) > a:nth-child(2)"))
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?: return null
        /** btih 非法则单条不可作为稳定结果。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        /** 元数据按语义字段或上源 nth-child 读取。 */
        val size = fieldText(entry, "size", "div:nth-child(1) > div:nth-last-child(2) > span:nth-child(2) > span")
        /** 做种数非法时保持 null。 */
        val seeders = fieldText(entry, "seeders", "div:nth-child(1) > div:nth-last-child(1) > span:nth-child(1) > span:nth-child(2)")?.toUIntOrNull()
        /** 下载人数非法时保持 null。 */
        val peers = fieldText(entry, "peers", "div:nth-child(1) > div:nth-last-child(1) > span:nth-child(2) > span:nth-child(2)")?.toUIntOrNull()
        /** 上传日期支持固定日期和上源相对日期，非法时为空。 */
        val uploadEpochMillis = parseBitSearchDate(
            fieldText(entry, "date", "div:nth-child(1) > div:nth-last-child(2) > span:nth-child(3) > span"),
            currentTimeMillis(),
        )
        /** 类别文本映射为稳定 Category，未知回退 OTHER。 */
        val category = mapBitSearchCategory(
            fieldText(entry, "category", "div:nth-child(1) > div:nth-last-child(2) > span:nth-child(1) > span").orEmpty(),
        )
        /** torrent 文件地址可空，但存在时必须保持同 host HTTPS。 */
        val fileDownloadLink = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            (entry.selectFirst("a[data-field=file]") ?: entry.selectFirst("> div:nth-child(2) > a:nth-child(1)"))?.attr("href"),
        )
        /** 详情地址来自名称 anchor，可空但不得跨 host。 */
        val descriptionPageUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, nameElement.selectFirst("a")?.attr("href"))
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = size,
            seeders = seeders,
            peers = peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            descriptionPageUrl = descriptionPageUrl,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }
}

/** BitSearch 详情 parser；详情成功必须包含名称和合法 btih。 */
private class BitSearchDetailsParser(
    /** TorrentDetails 稳定 Provider 字段。 */
    private val descriptor: ProviderDescriptor,
    /** torrent 下载链接精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析详情 DOM；缺顶层 main 为协议异常，缺必填字段返回 null。 */
    suspend fun parse(html: String, pageUrl: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** Jsoup 静态 DOM 不执行任何脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("main") == null) throw ProviderParseException()
        /** 名称优先语义 fixture，再回退页面唯一 h1。 */
        val name = (document.selectFirst("[data-field=name]") ?: document.selectFirst("h1"))
            ?.text()
            ?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** magnet 优先语义 fixture，再回退任意 magnet anchor。 */
        val magnetUri = (document.selectFirst("a[data-field=magnet]") ?: document.selectFirst("""a[href^="magnet:?"]"""))
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** 详情 btih 非法时返回 null。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** 展示大小可选。 */
        val size = (document.selectFirst("[data-field=size]") ?: document.selectFirst(DETAILS_SIZE))
            ?.text()
            ?.takeIf(String::isNotBlank)
        /** 做种数可选。 */
        val seeders = (document.selectFirst("[data-field=seeders]") ?: document.selectFirst(DETAILS_SEEDERS))
            ?.text()
            ?.toUIntOrNull()
        /** 下载人数可选。 */
        val peers = (document.selectFirst("[data-field=peers]") ?: document.selectFirst(DETAILS_PEERS))
            ?.text()
            ?.toUIntOrNull()
        /** 上传日期严格 M/d/yyyy，非法值降级为空。 */
        val uploadEpochMillis = (document.selectFirst("[data-field=upload-date]") ?: document.selectFirst(DETAILS_UPLOAD_DATE))
            ?.text()
            /** rawDate 是当前上传日期展示值。 */
            ?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "M/d/yyyy") }
        /** 最后检查日期严格 M/d/yyyy。 */
        val lastCheckedEpochMillis = (document.selectFirst("[data-field=last-checked]") ?: document.selectFirst(DETAILS_LAST_CHECKED))
            ?.text()
            /** rawDate 是当前最后检查日期展示值。 */
            ?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "M/d/yyyy") }
        /** 类别未知时回退 OTHER。 */
        val category = mapBitSearchCategory(
            (document.selectFirst("[data-field=category]") ?: document.selectFirst(DETAILS_CATEGORY))?.text().orEmpty(),
        )
        /** torrent 文件链接存在时必须保持同 host HTTPS。 */
        val fileDownloadLink = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            (document.selectFirst("a[data-field=file]") ?: document.selectFirst("""a[href^="/download/torrent/"]"""))?.attr("href"),
        )
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
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }

    /** 固定快照 BitSearch 详情页选择器；语义 fixture 缺失时才使用。 */
    private companion object {
        /** 顶部统计网格中的大小。 */
        private const val DETAILS_SIZE = "div.lg\\:col-span-2 > div.bg-white div.grid.grid-cols-2 > div:nth-child(3) > div.text-lg"
        /** 顶部统计网格中的做种数。 */
        private const val DETAILS_SEEDERS = "div.lg\\:col-span-2 > div.bg-white div.grid.grid-cols-2 > div:nth-child(1) > div.text-lg"
        /** 顶部统计网格中的下载人数。 */
        private const val DETAILS_PEERS = "div.lg\\:col-span-2 > div.bg-white div.grid.grid-cols-2 > div:nth-child(2) > div.text-lg"
        /** 信息卡第一行的上传日期。 */
        private const val DETAILS_UPLOAD_DATE = "div.lg\\:col-span-2 > div.flex.flex-col.gap-6 > div:nth-child(1) div.space-y-4 > div > div:nth-child(1) > div.text-sm.font-bold"
        /** 信息卡第二行的最后检查日期。 */
        private const val DETAILS_LAST_CHECKED = "div.lg\\:col-span-2 > div.flex.flex-col.gap-6 > div:nth-child(1) div.space-y-4 > div > div:nth-child(2) > div.text-sm.font-bold"
        /** 顶部标题区域的类别 badge。 */
        private const val DETAILS_CATEGORY = "div.lg\\:col-span-2 > div.bg-white span.inline-flex.items-center"
    }
}

/** 按语义 data-field 或固定上源 selector 读取非空文本。 */
private fun fieldText(entry: Element, field: String, fallbackSelector: String): String? =
    (entry.selectFirst("[data-field=$field]") ?: entry.selectFirst(fallbackSelector))
        ?.text()
        ?.takeIf(String::isNotBlank)

/** BitSearch 日期先尝试固定 M/d/yyyy，再使用注入时钟的相对日期语义。 */
private fun parseBitSearchDate(rawDate: String?, currentEpochMillis: Long): Long? {
    if (rawDate.isNullOrBlank()) return null
    return ProviderDateParser.parseUtcEpochMillis(rawDate, "M/d/yyyy")
        ?: ProviderDateParser.parseRelativeEpochMillis(rawDate, currentEpochMillis)
}

/** 固定映射 BitSearch 类别文本；未知值不依赖枚举 name 并回退 OTHER。 */
private fun mapBitSearchCategory(rawCategory: String): Category = when (rawCategory.trim()) {
    "Movies", "Movies/Dub/Dual Audio" -> Category.MOVIES
    "TV" -> Category.SERIES
    "Anime", "Anime/Dub/Dual Audio", "Anime/Subbed", "Anime/Raw" -> Category.ANIME
    "Softwares", "Softwares/Windows", "Softwares/Mac", "Softwares/Android" -> Category.APPS
    "Games", "Games/PC", "Games/Mac", "Games/Linux", "Games/Android" -> Category.GAMES
    "Music", "Music/mp3", "Music/Lossless", "Music/Album", "Music/Video" -> Category.MUSIC
    "AudioBook", "Ebook/Course" -> Category.BOOKS
    "XXX" -> Category.PORN
    else -> Category.OTHER
}
