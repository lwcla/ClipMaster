package com.cla.clip.feature.torrent.search.provider.builtin.btdigg

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 固定快照 BTDigg search/details Provider；结果保留通过 btih 校验的上源 magnet。 */
internal class BtDiggProvider(
    /** 功能内共享网络客户端，也是唯一网络入口。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期使用的可注入当前毫秒。 */
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** Jsoup、日期、btih 与领域映射使用的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider {
    /** BTDigg 稳定能力、安全等级与默认关闭状态。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("btdigg"),
        displayName = "BTDigg",
        baseUrl = BASE_URL,
        supportedCategories = setOf(Category.OTHER),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** manifest 审计通过的唯一精确 HTTPS host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("btdig.com"))
    /** 结果页 parser 不持有网络能力。 */
    private val resultsParser = BtDiggResultsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 详情 parser 不持有网络能力。 */
    private val detailsParser = BtDiggDetailsParser(descriptor, currentTimeMillis, parsingDispatcher)

    /** 构造编码查询 URL，网络读取留在调用协程，解析进入计算调度器。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 查询由 HttpUrl 编码且先删除控制字符。 */
        val requestUrl = BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", sanitizeProviderQuery(query))
            .build()
            .toString()
        /** 搜索页面网络读取保持调用协程。 */
        val responseHtml = networkClient.getText(requestUrl)
        return resultsParser.parse(responseHtml, requestUrl)
    }

    /** 详情输入先校验精确 host，再读取和解析。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 网络前校验的同站详情 URL。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 详情 HTML 不进入日志或异常。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        return detailsParser.parse(responseHtml)
    }

    /** BTDigg 固定网络常量。 */
    private companion object {
        /** manifest 审计通过的根地址。 */
        private const val BASE_URL = "https://btdig.com"
    }
}

/** BTDigg 搜索结果无网络解析器。 */
private class BtDiggResultsParser(
    /** Torrent 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 详情链接精确 host 策略。 */
    private val urlPolicy: BuiltinProviderUrlPolicy,
    /** 相对日期注入时刻。 */
    private val currentTimeMillis: () -> Long,
    /** DOM、日期、btih 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 解析结果；空容器返回空，缺顶层结果标记抛低敏异常。 */
    suspend fun parse(html: String, pageUrl: String): List<Torrent> = withContext(parsingDispatcher) {
        /** Jsoup 静态 DOM 不执行脚本。 */
        val document = Jsoup.parse(html, pageUrl)
        /** 固定上源结果节点集合。 */
        val entries = document.select("div.one_result > div")
        if (entries.isEmpty() && document.selectFirst("main#search-results") == null) throw ProviderParseException()
        /** entry 是当前结果节点，逐条解析前检查取消。 */
        entries.mapNotNullCancellable { entry -> parseEntry(entry, pageUrl) }
    }

    /** 单条名称、magnet 或 btih 缺失时跳过；恶意详情链接稳定拒绝。 */
    private fun parseEntry(entry: Element, pageUrl: String): Torrent? {
        /** 名称 anchor 同时提供详情候选地址。 */
        val nameAnchor = entry.selectFirst("div.torrent_name > a") ?: return null
        /** 空名称不可形成可见结果。 */
        val name = nameAnchor.text().takeIf(String::isNotBlank) ?: return null
        /** magnet URI 必填且保留原值供下载使用。 */
        val magnetUri = entry.selectFirst("div.torrent_magnet > div.fa-magnet > a")
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?: return null
        /** 规范化 infoHash 是结果稳定键。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return null
        /** 大小保持上源展示文本。 */
        val size = entry.selectFirst("span.torrent_size")?.ownText()?.takeIf(String::isNotBlank)
        /** found 前缀移除后按注入时刻解析相对日期。 */
        val uploadEpochMillis = entry.selectFirst("span.torrent_age")
            ?.ownText()
            ?.removePrefix("found ")
            /** rawDate 是去除固定前缀后的相对日期。 */
            ?.let { rawDate -> ProviderDateParser.parseRelativeEpochMillis(rawDate, currentTimeMillis()) }
        /** 详情链接可空；存在时必须同站 HTTPS。 */
        val descriptionPageUrl = urlPolicy.resolveOptionalHttpsUrl(pageUrl, nameAnchor.attr("href"))
        return Torrent(
            infoHash = infoHash,
            name = name,
            size = size,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = Category.OTHER,
            descriptionPageUrl = descriptionPageUrl,
            magnetUri = magnetUri,
        )
    }
}

/** BTDigg 详情无网络解析器。 */
private class BtDiggDetailsParser(
    /** TorrentDetails 稳定 Provider 字段来源。 */
    private val descriptor: ProviderDescriptor,
    /** 相对日期注入时刻。 */
    private val currentTimeMillis: () -> Long,
    /** DOM、日期、btih 与领域映射计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher,
) {
    /** 详情缺名称或合法 btih 返回 null，缺顶层协议容器抛低敏异常。 */
    suspend fun parse(html: String): TorrentDetails? = withContext(parsingDispatcher) {
        /** 详情 DOM 只在当前计算任务中存在。 */
        val document = Jsoup.parse(html)
        if (!hasDetailsContainer(document)) throw ProviderParseException()
        /** 名称单元格是详情必填字段。 */
        val name = propertyText(document, "Name:") ?: return@withContext null
        /** magnet URI 必填且保留上源值。 */
        val magnetUri = document.selectFirst("a[href^=magnet:?xt=]")
            ?.attr("href")
            ?.takeIf(String::isNotBlank)
            ?: return@withContext null
        /** btih 非法时详情不可用。 */
        val infoHash = MagnetInfoHashParser.parse(magnetUri) ?: return@withContext null
        /** 大小保持上源展示。 */
        val size = propertyText(document, "Size:")
        /** Age 补固定 ago 后按注入时刻解析，非法值降级为空。 */
        val uploadEpochMillis = propertyText(document, "Age:")?.let { rawAge ->
            ProviderDateParser.parseRelativeEpochMillis("$rawAge ago", currentTimeMillis())
        }
        TorrentDetails(
            infoHash = infoHash,
            name = name,
            size = size,
            providerId = descriptor.id,
            providerName = descriptor.displayName,
            uploadEpochMillis = uploadEpochMillis,
            category = Category.OTHER,
            magnetUri = magnetUri,
        )
    }

    /** 合成空容器用稳定 id，真实固定上源详情允许以 Name 属性表格识别。 */
    private fun hasDetailsContainer(document: Document): Boolean =
        document.selectFirst("main#torrent-details") != null || document.selectFirst("td:containsOwn(Name:)") != null

    /** 按固定 label 读取下一表格单元格的非空自有文本。 */
    private fun propertyText(document: Document, label: String): String? = document
        .selectFirst("td:containsOwn($label)")
        ?.nextElementSibling()
        ?.ownText()
        ?.takeIf(String::isNotBlank)
}
