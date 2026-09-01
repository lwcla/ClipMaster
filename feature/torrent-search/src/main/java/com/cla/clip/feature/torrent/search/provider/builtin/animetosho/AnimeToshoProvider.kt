package com.cla.clip.feature.torrent.search.provider.builtin.animetosho

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
import com.cla.clip.feature.torrent.search.provider.builtin.common.ProviderDateParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.mapNotNullCancellable
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** 固定快照 AnimeTosho 搜索与详情 Provider；站点 HTML 只用 Jsoup DOM 解析而不执行脚本。 */
internal class AnimeToshoProvider(
    /** 功能内共享网络边界，不允许 Provider 自行创建 HTTP 栈。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** Today/Yesterday 解析所需的可注入基准时间，默认读取系统毫秒。 */
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** Jsoup DOM 与逐条领域映射使用的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider {
    /** AnimeTosho 稳定元数据，安全等级与 manifest 审计一致。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("animetosho"),
        displayName = "AnimeTosho",
        baseUrl = BASE_URL,
        supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = true,
    )
    /** 详情输入和页面链接只允许 manifest 中的 animetosho.org。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("animetosho.org"))
    /** 搜索 DOM 解析器只接收稳定 Provider 元数据、URL 策略和可注入时间。 */
    private val resultsParser = AnimeToshoResultsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 详情 DOM 解析器与搜索共用同一白名单和时间边界。 */
    private val detailsParser = AnimeToshoDetailsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)

    /** 构造编码后搜索 URL、读取 HTML 并交给纯 DOM 解析器。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** q 参数使用 HttpUrl 编码，控制字符在构造前移除。 */
        val requestUrl = AnimeToshoUrlBuilder.buildSearchUrl(query)
        urlPolicy.requireHttpsUrl(requestUrl)
        /** 原始 HTML 只传给解析器，不进入日志或异常。 */
        val responseHtml = networkClient.getText(requestUrl)
        return resultsParser.parse(responseHtml, requestUrl)
    }

    /** 详情输入先校验 HTTPS/host，然后才能进入网络层。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 经白名单规范化的详情 URL，恶意 host 会在 getText 前抛出。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 详情原始 HTML 仅在内存解析，不记录正文。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        return detailsParser.parse(responseHtml, trustedDetailsUrl)
    }

    /** AnimeTosho 固定快照 URL 常量。 */
    private companion object {
        /** manifest 审计通过的唯一 HTTPS 根地址。 */
        private const val BASE_URL = "https://animetosho.org"
    }

    /** 集中构造 AnimeTosho URL，禁止业务类字符串拼接查询。 */
    private object AnimeToshoUrlBuilder {
        /** 使用 HttpUrl q 参数构造搜索地址。 */
        fun buildSearchUrl(query: String): String = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", sanitizeProviderQuery(query))
            .build()
            .toString()
    }
}

/** AnimeTosho 结果页解析器；顶层结构异常抛稳定异常，单条缺字段返回 null。 */
private class AnimeToshoResultsParser(
    /** 产出 Torrent 所需的 Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 解析出的详情与下载链接都通过此 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** Today/Yesterday 使用的可注入当前时间。 */
    private val currentTimeMillis: () -> Long,
    /** DOM 构建和逐条映射使用的可注入计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 在计算调度器解析搜索页；`#content` 存在且无条目表示合法空结果。 */
    suspend fun parse(html: String, pageUrl: String): List<Torrent> = withContext(parsingDispatcher) {
        /** Jsoup 只构建 DOM，不创建 WebView 或执行 script。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("#content") == null) throw ProviderParseException()
        /** entry 代表当前结果 DOM，共用协作遍历在每轮领域映射前响应取消。 */
        document.select("div.home_list_entry").mapNotNullCancellable { entry ->
            parseEntry(entry, pageUrl)
        }
    }

    /** 解析单条搜索结果；名称、大小、日期、links 或 magnet 缺失时跳过。 */
    private fun parseEntry(entry: Element, pageUrl: String): Torrent? {
        /** 标题链接同时提供名称和详情相对地址。 */
        val titleAnchor = entry.selectFirst("div.link > a") ?: return null
        /** 结果名称为必填，空白文本按缺失处理。 */
        val torrentName = titleAnchor.text().takeIf(String::isNotBlank) ?: return null
        /** 详情地址可空；存在时必须与当前页同一白名单。 */
        val detailsUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, titleAnchor.attr("href"))
        /** 上源展示大小是必填文本，不在此处重新换算。 */
        val size = entry.selectFirst("div.size")?.ownText()?.takeIf(String::isNotBlank) ?: return null
        /** 日期 title 去除固定前缀后交给可注入时间解析器。 */
        val rawDate = entry.selectFirst("div.date")
            ?.attr("title")
            ?.removePrefix(DATE_PREFIX)
            ?.trim()
            ?: return null
        /** 上传时间为 UTC epochMillis，不可解析时按必填缺失跳过。 */
        val uploadEpochMillis = ProviderDateParser.parseAnimeToshoEpochMillis(rawDate, currentTimeMillis()) ?: return null
        /** links 容器同时承载统计、torrent 下载和 magnet。 */
        val links = entry.selectFirst("div.links") ?: return null
        /** magnet 为必填且必须包含合法 btih。 */
        val magnetUri = links.selectFirst("""a[href^="magnet:"]""")?.attr("href")?.takeIf(String::isNotBlank) ?: return null
        /** 稳定主键从 magnet 规范化为小写 hex，失败时跳过单条。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        /** 做种和下载人数从同一统计 span 解析，缺失时回退 0。 */
        val seedersAndPeers = parseSeedersAndPeers(links)
        /** torrent 下载链接可空；存在时必须与页面同 host。 */
        val fileDownloadLink = urlPolicy.resolveOptionalHttpsUrl(pageUrl, links.selectFirst("a.dllink")?.attr("href"))
        return Torrent(
            infoHash = infoHash,
            name = torrentName,
            size = size,
            seeders = seedersAndPeers.first,
            peers = seedersAndPeers.second,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = Category.ANIME,
            descriptionPageUrl = detailsUrl,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
        )
    }

    /** 从统计 span 解析 `[seeders↑/peers↓]`；缺失或越界都安全回退 0/0。 */
    private fun parseSeedersAndPeers(links: Element): Pair<UInt, UInt> {
        /** 第一个带 title 的 span 是固定上源统计容器。 */
        val statisticsText = links.select("span[title]").firstOrNull()?.ownText().orEmpty()
        /** 捕获两个非负整数的固定格式匹配。 */
        val match = STATS_PATTERN.find(statisticsText)
        /** 做种数越界或缺失时回退 0。 */
        val seeders = match?.groupValues?.getOrNull(1)?.toUIntOrNull() ?: 0u
        /** 下载人数越界或缺失时回退 0。 */
        val peers = match?.groupValues?.getOrNull(2)?.toUIntOrNull() ?: 0u
        return seeders to peers
    }

    /** AnimeTosho 结果 DOM 的固定选择器与格式。 */
    private companion object {
        /** date title 中需要移除的固定英文前缀。 */
        private const val DATE_PREFIX = "Date/time submitted: "
        /** `[12↑/3↓]` 格式的做种/下载人数规则。 */
        private val STATS_PATTERN = Regex("""\[(\d+)↑/(\d+)↓]""")
    }
}

/** AnimeTosho 详情 DOM 解析器；缺失必填名称或 magnet 返回 null。 */
private class AnimeToshoDetailsParser(
    /** 产出详情所需的 Provider 稳定元数据。 */
    private val descriptor: ProviderDescriptor,
    /** 截图与 torrent 下载链接的精确 host 白名单。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** Today/Yesterday 详情日期使用的可注入时间。 */
    private val currentTimeMillis: () -> Long,
    /** DOM 构建、截图循环和详情映射使用的可注入计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 在计算调度器解析详情页；缺失顶层 content 容器视为结构异常。 */
    suspend fun parse(html: String, pageUrl: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** Jsoup DOM 仅在当前方法内存在，脚本节点不会执行。 */
        val document = Jsoup.parse(html, pageUrl)
        if (document.selectFirst("#content") == null) throw ProviderParseException()
        /** 详情名称为必填，空白标题返回 null。 */
        val torrentName = document.selectFirst("#title")?.ownText()?.takeIf(String::isNotBlank) ?: return@withContext null
        /** magnet 元素为必填，同时供大小文本回退查找下一个节点。 */
        val magnetElement = document.selectFirst("""a[href^="magnet:"]""") ?: return@withContext null
        /** magnet URI 为必填且不改写原始 tracker 参数。 */
        val magnetUri = magnetElement.attr("href").takeIf(String::isNotBlank) ?: return@withContext null
        /** 详情稳定 hash 从 magnet btih 规范化，非法时按缺必填字段返回 null。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** 大小优先读取 title 选择器，其次读 magnet 后紧邻文本节点。 */
        val rawSize = document.selectFirst("""span[title^="File size:"]""")?.ownText()
            /** node 是 magnet 后的紧邻节点，只接受纯文本以避免混入 HTML。 */
            ?: magnetElement.nextSibling()?.takeIf { node -> node is TextNode }?.nodeValue()
        /** 删除上源括号与分隔符后的可选大小文本。 */
        val size = rawSize?.trim()?.filterNot { character -> character == '(' || character == ')' || character == '|' }
            ?.takeIf(String::isNotBlank)
        /** 做种数可选，非法数字保持 null。 */
        val seeders = document.selectFirst("td[title=Seeders][align=right]")?.ownText()?.toUIntOrNull()
        /** 下载人数可选，非法数字保持 null。 */
        val peers = document.selectFirst("td[title=Leechers][align=right]")?.ownText()?.toUIntOrNull()
        /** 详情日期可选，支持 Today/Yesterday 和 dd/MM/yyyy HH:mm。 */
        val uploadEpochMillis = parseDetailsDate(
            document.selectFirst("#content > table:nth-of-type(1) > tbody > tr:nth-child(2) > td")?.ownText(),
        )
        /** anchor 是当前截图链接，共用协作遍历逐条响应取消并跳过空地址。 */
        val screenshotUrls = document.select("a.screenthumb").mapNotNullCancellable { anchor ->
            urlPolicy.resolveOptionalHttpsUrl(pageUrl, anchor.attr("href"))
        }
        /** torrent 文件地址可选且必须保持同 host HTTPS。 */
        val fileDownloadLink = urlPolicy.resolveOptionalHttpsUrl(
            pageUrl,
            document.selectFirst("a[href*=/storage/torrent]")?.attr("href"),
        )
        TorrentDetails(
            infoHash = infoHash,
            name = torrentName,
            size = size,
            seeders = seeders,
            peers = peers,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = Category.ANIME,
            magnetUri = magnetUri,
            fileDownloadLink = fileDownloadLink,
            screenshotUrls = screenshotUrls,
        )
    }

    /** 详情日期按相对日期或含时分的固定 UTC 格式解析。 */
    private fun parseDetailsDate(rawDate: String?): Long? {
        if (rawDate.isNullOrBlank()) return null
        return if (
            rawDate.startsWith("Today", ignoreCase = true) ||
            rawDate.startsWith("Yesterday", ignoreCase = true)
        ) {
            ProviderDateParser.parseAnimeToshoEpochMillis(rawDate, currentTimeMillis())
        } else {
            ProviderDateParser.parseUtcEpochMillis(rawDate.trim(), "dd/MM/yyyy HH:mm")
        }
    }
}
