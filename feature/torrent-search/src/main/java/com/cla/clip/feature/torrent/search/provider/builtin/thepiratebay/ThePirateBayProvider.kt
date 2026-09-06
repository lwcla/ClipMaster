package com.cla.clip.feature.torrent.search.provider.builtin.thepiratebay

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

/** ThePirateBay 请求编排；页面 token 与 API host 分离，JSON parser 不持有网络。 */
internal class ThePirateBayProvider(
    /** 功能内唯一网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON tree 和领域映射的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 本地 manifest 的 UNSAFE 与默认关闭结论覆盖其他来源描述。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("thepiratebay"),
        displayName = "ThePirateBay",
        baseUrl = ThePirateBayRequestBuilder.PAGE_BASE_URL,
        supportedCategories = setOf(
            Category.APPS,
            Category.BOOKS,
            Category.GAMES,
            Category.MOVIES,
            Category.MUSIC,
            Category.PORN,
            Category.SERIES,
            Category.OTHER,
        ),
        safetyLevel = ProviderSafetyLevel.UNSAFE,
        enabledByDefault = false,
    )

    /** 固定上源只声明该备用页面 host，不允许其承担 API 请求。 */
    override val alternateUrlDomains = listOf("knaben.xyz")

    /** 结果与详情共用显式字段 parser 和稳定 Provider 身份。 */
    private val jsonParser = ThePirateBayJsonParser(
        descriptor = descriptor,
        parsingDispatcher = parsingDispatcher,
    )

    /** search 只请求 apibay 固定端点，查询与类别均结构化编码。 */
    override suspend fun search(
        /** 原始搜索词只用于当前请求。 */
        query: String,
        /** 固定上源分类映射，ANIME 回退全部。 */
        category: Category,
    ): List<Torrent> = loadResults(ThePirateBayRequestBuilder.searchUrl(query, category))

    /** 详情入口先严格提取页面数字 ID，再请求 apibay 详情 API。 */
    override suspend fun getDetails(
        /** 未信任页面 token，必须在网络前完成完整结构校验。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** torrentId 是页面 token 中唯一有界正整数身份。 */
        val torrentId = ThePirateBayDetailsTokenParser.requireId(detailsPageUrl)
        /** responseJson 只从固定 API host 获取。 */
        val responseJson = networkClient.getJson(ThePirateBayRequestBuilder.detailsUrl(torrentId))
        return jsonParser.parseDetails(responseJson, torrentId)
    }

    /** ALL 使用 recent 预编译数据，其他分类使用固定 category 查询。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类，未知语义由固定映射收敛。 */
        category: Category,
    ): List<Torrent> = loadResults(ThePirateBayRequestBuilder.latestUrl(category))

    /** 热门列表使用固定 48 小时预编译文件且每次独立联网。 */
    override suspend fun getTopTorrents(
        /** 浏览分类，ALL 使用不带分类后缀的文件。 */
        category: Category,
    ): List<Torrent> = loadResults(ThePirateBayRequestBuilder.topUrl(category))

    /** 执行一次固定 JSON 请求并在响应后交给计算 parser。 */
    private suspend fun loadResults(
        /** 已由站点 builder 创建的固定 API URL。 */
        requestUrl: String,
    ): List<Torrent> {
        /** responseJson 不进入日志、异常或持久状态。 */
        val responseJson = networkClient.getJson(requestUrl)
        return jsonParser.parseResults(responseJson)
    }
}
