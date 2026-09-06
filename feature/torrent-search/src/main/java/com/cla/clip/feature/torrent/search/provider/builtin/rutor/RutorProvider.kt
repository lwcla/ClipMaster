package com.cla.clip.feature.torrent.search.provider.builtin.rutor

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

/** Rutor 固定快照 Provider；入口只编排同站网络与无网络 parser。 */
internal class RutorProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** manifest 固定元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("rutorinfo"),
        displayName = "Rutor",
        baseUrl = RutorUrlBuilder.BASE_URL,
        supportedCategories = setOf(
            Category.ALL,
            Category.ANIME,
            Category.APPS,
            Category.BOOKS,
            Category.GAMES,
            Category.MOVIES,
            Category.MUSIC,
            Category.SERIES,
            Category.OTHER,
        ),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )

    /** 同站请求与产出 URL 策略。 */
    private val urlPolicy = RutorUrlPolicy()

    /** 固定请求 URL 构造器。 */
    private val urlBuilder = RutorUrlBuilder()

    /** 列表 DOM parser。 */
    private val resultsParser = RutorResultsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 详情 DOM parser。 */
    private val detailsParser = RutorDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索按固定分类 path 发起一次请求。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> = loadResults(
        requestUrl = urlBuilder.searchUrl(query, RutorCategoryMapper.requestId(category)),
        resultCategory = resultCategory(category),
    )

    /** 详情 URL 在网络前校验，响应解析进入计算调度器。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是精确同站 HTTPS 地址。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 最新列表按调用分类构造固定 browse 路径。 */
    override suspend fun getLatestTorrents(
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> = loadResults(
        requestUrl = urlBuilder.latestUrl(RutorCategoryMapper.requestId(category)),
        resultCategory = resultCategory(category),
    )

    /** 热门路径固定，但结果仍保留调用方分类范围。 */
    override suspend fun getTopTorrents(
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> = loadResults(
        requestUrl = urlBuilder.topUrl(),
        resultCategory = resultCategory(category),
    )

    /** 获取列表 HTML 后交给无网络 parser。 */
    private suspend fun loadResults(
        /** 已结构化生成的请求地址。 */
        requestUrl: String,
        /** 输出结果的调用范围分类。 */
        resultCategory: Category?,
    ): List<Torrent> {
        /** trustedUrl 防止内部常量或 builder 漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl, resultCategory)
    }

    /** ALL 不应伪造来源分类，其他调用范围原样保留。 */
    private fun resultCategory(
        /** 调用方分类。 */
        category: Category,
    ): Category? = if (category == Category.ALL) null else category
}
