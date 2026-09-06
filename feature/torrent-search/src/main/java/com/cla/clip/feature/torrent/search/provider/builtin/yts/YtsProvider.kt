package com.cla.clip.feature.torrent.search.provider.builtin.yts

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

/** Yts API 请求编排；网络留在调用协程，JSON 与领域映射进入计算调度器。 */
internal class YtsProvider(
    /** 功能内唯一网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON tree 和领域映射的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定上源与 manifest 一致的安全元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("ytsmx"),
        displayName = "Yts",
        baseUrl = YtsUrlBuilder.PAGE_BASE_URL,
        supportedCategories = setOf(Category.MOVIES),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = true,
    )

    /** parser 不持有网络，只处理显式 JSON key 与输出 URL。 */
    private val jsonParser = YtsJsonParser(
        descriptor = descriptor,
        parsingDispatcher = parsingDispatcher,
    )

    /** 搜索固定 limit=50，查询由结构化 URL 编码。 */
    override suspend fun search(
        /** 原始搜索词只在 builder 内清洗与编码。 */
        query: String,
        /** Yts 只返回电影，统一接口分类不改变请求。 */
        category: Category,
    ): List<Torrent> = loadResults(YtsUrlBuilder.searchUrl(query))

    /** 详情 token 先在本地验证 movie ID 与 hash，再请求固定 API。 */
    override suspend fun getDetails(
        /** 未信任页面 token，必须在网络前严格校验。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** token 是页面 URL 中经过验证的稳定身份。 */
        val token = YtsDetailsTokenParser.requireToken(detailsPageUrl)
        /** responseJson 只从固定 API host 获取。 */
        val responseJson = networkClient.getJson(YtsUrlBuilder.detailsUrl(token.movieId))
        return jsonParser.parseDetails(responseJson, token)
    }

    /** latest 每次发起独立 list 请求。 */
    override suspend fun getLatestTorrents(
        /** Yts 只返回电影，分类不改变上源请求。 */
        category: Category,
    ): List<Torrent> = loadResults(YtsUrlBuilder.latestUrl())

    /** 固定上源 top 语义是再次请求 latest，不共享缓存或响应。 */
    override suspend fun getTopTorrents(
        /** Yts 只返回电影，分类不改变上源请求。 */
        category: Category,
    ): List<Torrent> = loadResults(YtsUrlBuilder.latestUrl())

    /** 执行一次固定 JSON 请求并将响应交给无网络 parser。 */
    private suspend fun loadResults(
        /** 由固定 builder 构造的 API URL。 */
        requestUrl: String,
    ): List<Torrent> {
        /** responseJson 只在内存解析，不进入日志。 */
        val responseJson = networkClient.getJson(requestUrl)
        return jsonParser.parseResults(responseJson)
    }
}
