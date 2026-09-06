package com.cla.clip.feature.torrent.search.provider.builtin.limetorrents

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 固定快照 LimeTorrents Provider；所有网络调用均留在调用协程。 */
internal class LimeTorrentsProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期使用的可注入当前毫秒。 */ currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** DOM、日期和领域映射使用的计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** LimeTorrents 的本地不安全审计、七类能力和默认关闭策略。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("limetorrents"), displayName = "LimeTorrents", baseUrl = BASE_URL,
        supportedCategories = SUPPORTED_CATEGORIES, safetyLevel = ProviderSafetyLevel.UNSAFE, enabledByDefault = false,
    )
    /** 站点详情与 iTorrents marker 的精确 URL 策略。 */ private val urlPolicy = LimeTorrentsUrlPolicy()
    /** 结构化搜索与浏览 URL 构造器。 */ private val urlBuilder = LimeTorrentsUrlBuilder()
    /** 结果页 parser 不持有网络。 */ private val resultsParser = LimeTorrentsResultsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 详情 parser 不持有网络。 */ private val detailsParser = LimeTorrentsDetailsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)

    /** 按固定分类与日期排序路径搜索。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = loadResults(urlBuilder.searchUrl(query, category), null)

    /** 详情 URL 在网络前按两个站点 host 校验。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** trustedUrl 是可访问的同站 HTTPS 默认端口地址。 */ val trustedUrl = urlPolicy.requireDetailsUrl(detailsPageUrl)
        /** responseHtml 只在内存传递。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml)
    }

    /** 不支持分类直接空返回；支持分类按固定大小写 browse 路径请求。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> =
        if (category !in SUPPORTED_CATEGORIES) emptyList() else loadResults(urlBuilder.latestUrl(category), category)

    /** 不支持分类直接空返回；支持分类按固定 top 路径请求。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> =
        if (category !in SUPPORTED_CATEGORIES) emptyList() else loadResults(urlBuilder.topUrl(category), category)

    /** 请求列表页并把可选搜索分类传给无网络 parser 作为回退。 */
    private suspend fun loadResults(
        /** 已结构化生成的固定同站 URL。 */ requestUrl: String,
        /** latest/top 已知分类；搜索为 null 并优先读取页面。 */ searchCategory: Category?,
    ): List<Torrent> {
        /** trustedUrl 防止内部 URL 构造漂移。 */ val trustedUrl = urlPolicy.requireDetailsUrl(requestUrl)
        /** responseHtml 在调用协程获取。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl, searchCategory)
    }

    /** LimeTorrents 固定元数据常量。 */
    private companion object {
        /** 固定主页。 */ private const val BASE_URL = "https://limetorrents.fun"
        /** 上源支持的七个分类；ALL/BOOKS/PORN 仅能走 search 回退。 */
        private val SUPPORTED_CATEGORIES = setOf(Category.ANIME, Category.APPS, Category.GAMES, Category.MOVIES, Category.MUSIC, Category.SERIES, Category.OTHER)
    }
}
