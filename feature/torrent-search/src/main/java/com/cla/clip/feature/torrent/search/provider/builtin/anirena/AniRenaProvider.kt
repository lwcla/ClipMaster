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
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 固定快照 AniRena search/details/latest/top Provider；本地 manifest 审计将其固定为 NSFW
 * 且默认关闭，不采用上源源码的 Safe 标记。
 */
internal class AniRenaProvider(
    /** 功能内共享网络边界，包括不跟随重定向的 magnet Location 读取。 */
    private val networkClient: TorrentSearchNetworkClient,
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
    /** 结果 DOM 解析器负责页面字段与受限 magnet 重定向。 */
    private val resultsParser = AniRenaResultsParser(descriptor, urlPolicy, networkClient)
    /** 详情 DOM 解析器复用同一白名单与重定向边界。 */
    private val detailsParser = AniRenaDetailsParser(descriptor, urlPolicy, networkClient)

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

    /** 详情 URL 先经 HTTPS/host 白名单校验，通过后才能请求页面。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 经精确 host 白名单规范化的详情地址。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 详情页原始 HTML 只传给 DOM 解析器，不记录正文。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        return detailsParser.parse(responseHtml, trustedDetailsUrl)
    }

    /** 统一执行 AniRena 结果页的白名单校验、网络读取与 DOM 解析。 */
    private suspend fun fetchResultsPage(requestUrl: String): List<Torrent> {
        /** 内部构造 URL 仍经过白名单，防止后续常量或 builder 修改绕过安全边界。 */
        val trustedRequestUrl = urlPolicy.requireHttpsUrl(requestUrl).toString()
        /** 结果页原始 HTML 只传给 DOM 解析器。 */
        val responseHtml = networkClient.getText(trustedRequestUrl)
        return resultsParser.parse(responseHtml, trustedRequestUrl)
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

/** AniRena 结果页解析器；只有合法行才发起受限 magnet 重定向请求。 */
private class AniRenaResultsParser(
    /** 产出 Torrent 所需的 Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 详情、下载和重定向来源的精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 只用于获取已校验重定向的 Location。 */
    private val networkClient: TorrentSearchNetworkClient,
) {
    /** 解析结果表；表格存在但 tbody 为空表示合法空结果。 */
    suspend fun parse(html: String, pageUrl: String): List<Torrent> {
        /** Jsoup 只构建 DOM，不执行详情描述或页面 script。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("table.tl-table") == null) throw ProviderParseException()
        /** 已逐条通过必填字段与 magnet 验证的结果列表。 */
        val torrents = mutableListOf<Torrent>()
        /** 按 DOM 顺序解析每行，单条缺字段跳过，安全拒绝则终止当前 Provider。 */
        for (row in document.select("table.tl-table > tbody > tr")) {
            /** 当前行的可空 Torrent，非空时才加入结果。 */
            val torrent = parseRow(row, pageUrl)
            if (torrent != null) torrents += torrent
        }
        return torrents
    }

    /** 解析单行并获取 magnet；名称或 magnet 来源缺失时跳过。 */
    private suspend fun parseRow(row: Element, pageUrl: String): Torrent? {
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
        /** 不跟随重定向获取的 Location，下一步仍需要验证 magnet/btih。 */
        val magnetUri = networkClient.redirectLocation(redirectUrl) ?: return null
        /** 稳定 infoHash 从 Location 规范化，非 magnet 或非法 btih 使用 HTTP 拒绝语义。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: throw HttpDeniedException()
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
        return Torrent(
            infoHash = infoHash,
            name = torrentName,
            size = row.selectFirst("td.col-size")?.ownText()?.takeIf(String::isNotBlank),
            seeders = row.selectFirst("td.col-se > span.tl-se")?.ownText()?.toUIntOrNull(),
            peers = row.selectFirst("td.col-le > span.tl-le")?.ownText()?.toUIntOrNull(),
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            descriptionPageUrl = detailsUrl,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }
}

/** AniRena 详情 DOM 解析器；脚本描述只读取 data 文本，不执行任何 JavaScript。 */
private class AniRenaDetailsParser(
    /** 产出详情所需的 Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 重定向、torrent 和海报链接的精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 只用于取得经白名单校验的 magnet Location。 */
    private val networkClient: TorrentSearchNetworkClient,
) {
    /** 解析 AniRena 详情页；缺失顶层详情容器视为结构异常。 */
    suspend fun parse(html: String, pageUrl: String): TorrentDetails? {
        /** Jsoup DOM 只用于选择节点，script 不会被执行。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("h1.td-title, code.td-ov-stat-hash, div.td-ov-stats") == null) {
            throw ProviderParseException()
        }
        /** 页面 hash 必须是合法 20 字节 btih，否则按缺必填字段返回 null。 */
        val infoHash = document.selectFirst("code.td-ov-stat-hash")
            ?.ownText()
            ?.let(MagnetInfoHashParser::normalize)
            ?: return null
        /** 详情名称为必填，空白时返回 null。 */
        val torrentName = document.selectFirst("h1.td-title")?.text()?.takeIf(String::isNotBlank) ?: return null
        /** 页面 magnet 来源可空，存在时必须先通过同 host HTTPS 白名单。 */
        val redirectUrl = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("div.td-actions > a:nth-child(1)")?.attr("href"),
        )
        /** 有跳转时使用受限 Location，无跳转时从页面 hash 生成最小 magnet。 */
        val magnetUri = if (redirectUrl != null) {
            /** 跳转 Location 必须存在且是合法 magnet/btih。 */
            val redirectedMagnet = networkClient.redirectLocation(redirectUrl) ?: throw HttpDeniedException()
            if (MagnetInfoHashParser.parse(redirectedMagnet) == null) throw HttpDeniedException()
            redirectedMagnet
        } else {
            MagnetInfoHashParser.create(infoHash)
        }
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
        return TorrentDetails(
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
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = category,
            uploader = document.selectFirst("div.td-ov-meta > div.td-ov-meta-val:nth-child(4)")
                ?.ownText()
                ?.takeIf(String::isNotBlank),
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
            description = description,
            posterUrl = posterUrl,
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
