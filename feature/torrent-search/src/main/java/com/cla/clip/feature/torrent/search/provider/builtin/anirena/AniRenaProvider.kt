package com.cla.clip.feature.torrent.search.provider.builtin.anirena

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 固定快照 AniRena search/details/latest/top Provider；页面/重定向网络编排留在调用协程，
 * DOM 与领域映射切到计算调度器；本地审计固定为 NSFW 且默认关闭。
 */
internal class AniRenaProvider(
    /** 功能内共享网络边界，包括不跟随重定向的 magnet Location 读取。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** Jsoup DOM 和逐条领域映射使用的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, LatestTorrentsProvider, TopTorrentsProvider, TorrentDetailsProvider {
    /** AniRena 稳定 ID、能力、分类与本地 NSFW 安全结论。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("anirena"),
        displayName = "AniRena",
        baseUrl = BASE_URL,
        supportedCategories = setOf(
            Category.ANIME,
            Category.APPS,
            Category.BOOKS,
            Category.MUSIC,
            Category.PORN,
            Category.SERIES,
            Category.OTHER,
        ),
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
    )
    /** manifest 只允许 anirena.com 精确 host，不自动放行任何子域。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("anirena.com"))
    /** 结果 parser 只处理 DOM/draft/领域映射，不持有网络客户端。 */
    private val resultsParser = AniRenaResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情 parser 只处理 DOM/draft/领域映射，不触发重定向。 */
    private val detailsParser = AniRenaDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 按查询和可选分类读取第一页搜索结果。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** q/page/cat 由 HttpUrl 按稳定顺序编码的搜索 URL。 */
        val requestUrl = AniRenaUrlBuilder.buildSearchUrl(query, category)
        return fetchResultsPage(requestUrl)
    }

    /** 按分类读取首页最新资源；不含 q 参数。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> {
        /** page/cat 由 HttpUrl 编码的 latest URL。 */
        val requestUrl = AniRenaUrlBuilder.buildBrowseUrl(category)
        return fetchResultsPage(requestUrl)
    }

    /** 固定上源的 top 与 latest 共用首页排列，但每次调用仍执行独立请求。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> {
        /** page/cat 由 HttpUrl 编码的 top URL，当前与 latest 形式相同。 */
        val requestUrl = AniRenaUrlBuilder.buildBrowseUrl(category)
        return fetchResultsPage(requestUrl)
    }

    /** 详情页面先网络读取、再计算解析、再在调用上下文读取可选 magnet Location。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 经精确 host 白名单规范化的详情地址。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 网络请求保持调用上下文，不进入 parsingDispatcher。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        /** DOM 解析后得到不含网络结果的详情 draft。 */
        val detailsDraft = detailsParser.parseDraft(responseHtml, trustedDetailsUrl) ?: return null
        /** 有跳转时在调用上下文读取 Location；无跳转时恢复固定 tracker 的 canonical magnet。 */
        val magnetUri = if (detailsDraft.redirectUrl != null) {
            /** 已通过 host 白名单的重定向地址，只在网络边界使用。 */
            val redirectedMagnet = networkClient.redirectLocation(detailsDraft.redirectUrl) ?: throw HttpDeniedException()
            if (MagnetInfoHashParser.parse(redirectedMagnet) == null) throw HttpDeniedException()
            redirectedMagnet
        } else {
            MagnetInfoHashParser.create(detailsDraft.infoHash)
        }
        return detailsParser.mapDetails(detailsDraft, magnetUri)
    }

    /** 统一执行结果页白名单、网络读取、DOM draft、重定向及计算映射。 */
    private suspend fun fetchResultsPage(requestUrl: String): List<Torrent> {
        /** 内部构造 URL 仍经过白名单，防止常量或 builder 修改绕过安全边界。 */
        val trustedRequestUrl = urlPolicy.requireHttpsUrl(requestUrl).toString()
        /** 页面网络请求保持调用上下文。 */
        val responseHtml = networkClient.getText(trustedRequestUrl)
        /** DOM 和逐行字段提取在计算调度器完成，不触发任何网络。 */
        val drafts = resultsParser.parseDrafts(responseHtml, trustedRequestUrl)
        /** 最终可见 torrent 按 DOM 顺序收集。 */
        val torrents = mutableListOf<Torrent>()
        /** draft 是当前合法行；每次重定向前先响应取消。 */
        for (draft in drafts) {
            currentCoroutineContext().ensureActive()
            /** magnet Location 网络请求保持调用上下文，空 Location 按单条缺失跳过。 */
            val magnetUri = networkClient.redirectLocation(draft.redirectUrl) ?: continue
            /** 非 magnet 或非法 btih 使用稳定 HTTP 拒绝语义。 */
            val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: throw HttpDeniedException()
            torrents += resultsParser.mapTorrent(draft, magnetUri, infoHash)
        }
        return torrents
    }

    /** AniRena 固定快照 URL 常量。 */
    private companion object {
        /** manifest 审计通过的唯一 HTTPS 根地址。 */
        private const val BASE_URL = "https://anirena.com"
    }

    /** 集中 AniRena URL 和分类参数构造，避免任意查询字符串拼接。 */
    private object AniRenaUrlBuilder {
        /** 将领域分类映射为固定上源 cat 参数，ALL 与不支持分类不发送 cat。 */
        private val categoryValues = mapOf(
            Category.ANIME to "anime",
            Category.APPS to "software",
            Category.BOOKS to "manga",
            Category.MUSIC to "audio",
            Category.PORN to "hentai",
            Category.SERIES to "live",
            Category.OTHER to "other",
        )

        /** 构造 q/page/cat 搜索 URL，查询控制字符在交给 HttpUrl 前移除。 */
        fun buildSearchUrl(query: String, category: Category): String {
            /** 从受信根地址创建的 URL builder。 */
            val builder = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", sanitizeProviderQuery(query))
                .addQueryParameter("page", "1")
            /** 只在存在固定映射时添加 cat，避免枚举 name 或 ordinal 进入协议。 */
            val categoryValue = categoryValues[category]
            if (categoryValue != null) builder.addQueryParameter("cat", categoryValue)
            return builder.build().toString()
        }

        /** 构造不含查询词的 page/cat 浏览 URL。 */
        fun buildBrowseUrl(category: Category): String {
            /** 从受信根地址创建的 URL builder。 */
            val builder = BASE_URL.toHttpUrl().newBuilder().addQueryParameter("page", "1")
            /** 只在存在固定映射时添加 cat。 */
            val categoryValue = categoryValues[category]
            if (categoryValue != null) builder.addQueryParameter("cat", categoryValue)
            return builder.build().toString()
        }
    }
}

/** AniRena 结果行在 DOM 解析与重定向网络之间传递的低敏内部 draft。 */
private data class AniRenaResultDraft(
    /** 必填结果名称，禁止写日志。 */
    val name: String,
    /** 已通过同 host HTTPS 白名单的 magnet 重定向地址。 */
    val redirectUrl: String,
    /** 可选展示大小。 */
    val size: String?,
    /** 可选做种数。 */
    val seeders: UInt?,
    /** 可选下载人数。 */
    val peers: UInt?,
    /** 可选 UTC 上传时间毫秒。 */
    val uploadEpochMillis: Long?,
    /** 可选稳定分类。 */
    val category: Category?,
    /** 可选详情地址。 */
    val detailsUrl: String?,
    /** 可选 torrent 文件地址。 */
    val fileDownloadLink: String?,
)

/** AniRena 结果页 parser；DOM 与逐条映射在计算调度器执行且不持有网络能力。 */
private class AniRenaResultsParser(
    /** 产出 Torrent 所需的稳定 Provider 元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 详情、下载和重定向来源的精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 与领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 在计算调度器解析结果表；表格存在但 tbody 为空表示合法空结果。 */
    suspend fun parseDrafts(html: String, pageUrl: String): List<AniRenaResultDraft> = withContext(parsingDispatcher) {
        /** Jsoup 只构建 DOM，不执行详情描述或页面 script。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("table.tl-table") == null) throw ProviderParseException()
        /** row 是当前 DOM 行，共用协作遍历在每轮字段提取前响应取消。 */
        document.select("table.tl-table > tbody > tr").mapNotNullCancellable { row ->
            parseRow(row, pageUrl)
        }
    }

    /** 在计算调度器把已解析 draft 与受信 magnet 映射为领域 Torrent。 */
    suspend fun mapTorrent(draft: AniRenaResultDraft, magnetUri: String, infoHash: String): Torrent =
        withContext(parsingDispatcher) {
            currentCoroutineContext().ensureActive()
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
                descriptionPageUrl = draft.detailsUrl,
                magnetUri = magnetUri,
                fileDownloadLink = draft.fileDownloadLink,
            )
        }

    /** 从单行提取不含网络结果的 draft；名称或重定向来源缺失时返回 null。 */
    private fun parseRow(row: Element, pageUrl: String): AniRenaResultDraft? {
        /** 名称链接是结果的必填主体。 */
        val titleAnchor = row.selectFirst("td.col-name > div.tl-name-wrap > a.tl-torrent-name") ?: return null
        /** 空白名称按缺失必填字段跳过。 */
        val torrentName = titleAnchor.ownText().takeIf(String::isNotBlank) ?: return null
        /** 第一个操作链接是 magnet 重定向来源。 */
        val redirectCandidate = row.selectFirst("td.col-actions > div.tl-actions > a:nth-child(1)")
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?: return null
        /** 重定向来源先解析为同 host HTTPS，恶意地址不能进入 networkClient。 */
        val redirectUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, redirectCandidate) ?: return null
        /** 秒级 Unix 时间安全转换为 epochMillis，缺失或溢出保持 null。 */
        val uploadEpochMillis = row.attr("data-created-ts")
            .takeIf(String::isNotBlank)
            ?.toLongOrNull()
            ?.let(ProviderDateParser::epochSecondsToMillis)
        /** 结果分类只取 `/` 前的上源主类别文本。 */
        val category = row.selectFirst("td.col-cat")
            ?.attr("title")
            ?.takeIf(String::isNotBlank)
            ?.substringBefore('/')
            ?.trim()
            ?.let(::mapAniRenaCategory)
        /** 详情链接与当前页共用同一 host 白名单。 */
        val detailsUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, titleAnchor.attr("href"))
        /** 第二个操作链接是可选 torrent 文件下载地址。 */
        val fileDownloadLink = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            row.selectFirst("td.col-actions > div.tl-actions > a:nth-child(2)")?.attr("href"),
        )
        return AniRenaResultDraft(
            name = torrentName,
            redirectUrl = redirectUrl,
            size = row.selectFirst("td.col-size")?.ownText()?.takeIf(String::isNotBlank),
            seeders = row.selectFirst("td.col-se > span.tl-se")?.ownText()?.toUIntOrNull(),
            peers = row.selectFirst("td.col-le > span.tl-le")?.ownText()?.toUIntOrNull(),
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            detailsUrl = detailsUrl,
            fileDownloadLink = fileDownloadLink,
        )
    }
}

/** AniRena 详情 DOM 解析与网络重定向之间传递的内部 draft。 */
private data class AniRenaDetailsDraft(
    /** 页面规范化 infoHash。 */
    val infoHash: String,
    /** 详情名称。 */
    val name: String,
    /** 可选展示大小。 */
    val size: String?,
    /** 可选做种数。 */
    val seeders: UInt?,
    /** 可选下载人数。 */
    val peers: UInt?,
    /** 可选 UTC 上传时间毫秒。 */
    val uploadEpochMillis: Long?,
    /** 可选稳定分类。 */
    val category: Category?,
    /** 可选发布者。 */
    val uploader: String?,
    /** 可选且已通过白名单的 magnet 重定向地址。 */
    val redirectUrl: String?,
    /** 可选 torrent 文件地址。 */
    val fileDownloadLink: String?,
    /** 只读取未执行的 script data 描述。 */
    val description: String?,
    /** 可选海报地址。 */
    val posterUrl: String?,
)

/** AniRena 详情 parser；脚本描述只读取 data 文本，不执行 JavaScript，也不持有网络能力。 */
private class AniRenaDetailsParser(
    /** 产出详情所需的 Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 重定向、torrent 和海报链接的精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** DOM 和最终领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 在计算调度器解析 AniRena 详情 draft；缺失顶层详情容器视为结构异常。 */
    suspend fun parseDraft(html: String, pageUrl: String): AniRenaDetailsDraft? = withContext(parsingDispatcher) {
        /** Jsoup DOM 只用于选择节点，script 不会被执行。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("h1.td-title, code.td-ov-stat-hash, div.td-ov-stats") == null) {
            throw ProviderParseException()
        }
        currentCoroutineContext().ensureActive()
        /** 页面 hash 必须是合法 20 字节 btih，否则按缺必填字段返回 null。 */
        val infoHash = document.selectFirst("code.td-ov-stat-hash")
            ?.ownText()
            ?.let(MagnetInfoHashParser::normalize)
            ?: return@withContext null
        /** 详情名称为必填，空白时返回 null。 */
        val torrentName = document.selectFirst("h1.td-title")?.text()?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** 页面 magnet 来源可空，存在时必须先通过同 host HTTPS 白名单。 */
        val redirectUrl = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("div.td-actions > a:nth-child(1)")?.attr("href"),
        )
        /** AniRena 详情页时间为无时区 UTC `yyyy-MM-dd HH:mm`。 */
        val uploadEpochMillis = document.selectFirst("div.td-ov-meta > div.td-ov-meta-val:nth-child(6) > span")
            ?.attr("data-utc")
            ?.takeIf(String::isNotBlank)
            /** rawDate 是站点提供的无时区 UTC 时间，严格按固定格式转换。 */
            ?.let { rawDate -> ProviderDateParser.parseUtcEpochMillis(rawDate, "yyyy-MM-dd HH:mm") }
        /** 站点分类文本映射为稳定领域 Category。 */
        val category = document.selectFirst("div.td-ov-meta-val:nth-child(2) a.td-cat-name.td-cat-link")
            ?.ownText()
            ?.let(::mapAniRenaCategory)
        /** torrent 文件下载链接可空，存在时必须保持同 host HTTPS。 */
        val fileDownloadLink = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("div.td-actions > a:nth-child(2)")?.attr("href"),
        )
        /** script 节点只读取原始 data 并去除一层 JSON 引号，不执行内容。 */
        val description = document.selectFirst("script#td-description-raw")
            ?.data()
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)
        /** 海报链接可空，存在时必须与详情页同 host HTTPS。 */
        val posterUrl = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("div.td-anime-poster > img")?.attr("src"),
        )
        AniRenaDetailsDraft(
            infoHash = infoHash,
            name = torrentName,
            size = document.selectFirst("div.td-ov-stats > div.td-ov-stat:nth-child(4) > div.td-ov-stat-num")
                ?.ownText()
                ?.takeIf(String::isNotBlank),
            seeders = document.selectFirst("div.td-ov-stats > div.td-ov-stat--se > div.td-ov-stat-num")
                ?.ownText()
                ?.toUIntOrNull(),
            peers = document.selectFirst("div.td-ov-stats > div.td-ov-stat--le > div.td-ov-stat-num")
                ?.ownText()
                ?.toUIntOrNull(),
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            uploader = document.selectFirst("div.td-ov-meta > div.td-ov-meta-val:nth-child(4)")
                ?.ownText()
                ?.takeIf(String::isNotBlank),
            redirectUrl = redirectUrl,
            fileDownloadLink = fileDownloadLink,
            description = description,
            posterUrl = posterUrl,
        )
    }

    /** 在计算调度器把详情 draft 与已验证 magnet 映射为领域详情。 */
    suspend fun mapDetails(draft: AniRenaDetailsDraft, magnetUri: String): TorrentDetails =
        withContext(parsingDispatcher) {
            currentCoroutineContext().ensureActive()
            TorrentDetails(
                infoHash = draft.infoHash,
                name = draft.name,
                size = draft.size,
                seeders = draft.seeders,
                peers = draft.peers,
                providerId = descriptor.id,
                providerName = descriptor.displayName,
                uploadEpochMillis = draft.uploadEpochMillis,
                category = draft.category,
                uploader = draft.uploader,
                magnetUri = magnetUri,
                fileDownloadLink = draft.fileDownloadLink,
                description = draft.description,
                posterUrl = draft.posterUrl,
            )
        }
}

/** 将 AniRena 原始分类名映射为稳定领域 Category，未知值保守回退 OTHER。 */
private fun mapAniRenaCategory(rawCategory: String): Category = when (rawCategory.trim()) {
    "Anime" -> Category.ANIME
    "Manga", "Literature" -> Category.BOOKS
    "Audio" -> Category.MUSIC
    "Live Action" -> Category.SERIES
    "Software" -> Category.APPS
    "Hentai" -> Category.PORN
    else -> Category.OTHER
}
