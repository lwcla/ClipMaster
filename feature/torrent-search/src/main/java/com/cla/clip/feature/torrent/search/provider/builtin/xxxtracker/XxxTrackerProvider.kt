package com.cla.clip.feature.torrent.search.provider.builtin.xxxtracker

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

/** XXXTracker 固定快照 Provider；入口只编排同站网络与无网络 parser。 */
internal class XxxTrackerProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** manifest 固定元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("xxxtracker"),
        displayName = "XXXTracker",
        baseUrl = XxxTrackerUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.PORN),
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
    )

    /** 同站请求与产出 URL 策略。 */
    private val urlPolicy = XxxTrackerUrlPolicy()

    /** 固定请求 URL 构造器。 */
    private val urlBuilder = XxxTrackerUrlBuilder()

    /** 列表 DOM parser。 */
    private val resultsParser = XxxTrackerResultsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 详情 DOM parser。 */
    private val detailsParser = XxxTrackerDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索使用结构化 query 参数发起一次请求。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> = loadResults(urlBuilder.searchUrl(query))

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

    /** 最新能力使用固定 b.php 路径。 */
    override suspend fun getLatestTorrents(
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> = loadResults(urlBuilder.latestUrl())

    /** 热门能力使用固定 top 路径。 */
    override suspend fun getTopTorrents(
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> = loadResults(urlBuilder.topUrl())

    /** 获取列表 HTML 后交给无网络 parser。 */
    private suspend fun loadResults(
        /** 已结构化生成的请求地址。 */
        requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部常量或 builder 漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }
}
