package com.cla.clip.feature.torrent.search.provider.builtin.torrentdatabase

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

/** TorrentDatabase 固定快照入口；网络与离线解析保持明确边界。 */
internal class TorrentDatabaseProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM、日期与领域映射调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** manifest 审计确认成人分类、Cloudflare 保护与默认关闭。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("torrentdatabase"),
        displayName = "TorrentDatabase",
        baseUrl = BASE_URL,
        supportedCategories = SUPPORTED_CATEGORIES,
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
        isCloudflareProtected = true,
    )
    /** 网络输入、详情输出与 marker 的精确 URL 策略。 */ private val urlPolicy = TorrentDatabaseUrlPolicy()
    /** search/latest/top 路径构造器。 */ private val urlBuilder = TorrentDatabaseUrlBuilder()
    /** 列表 parser 不持有网络。 */ private val resultsParser = TorrentDatabaseResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情 parser 不持有网络。 */ private val detailsParser = TorrentDatabaseDetailsParser(descriptor, parsingDispatcher)

    /** 搜索 query 和可选类别参数由 HttpUrl 结构化编码。 */
    override suspend fun search(
        /** 用户查询。 */ query: String,
        /** 搜索分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.searchUrl(query, category))

    /** 网络前拒绝非审核 authority。 */
    override suspend fun getDetails(
        /** 外部详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是规范化后的同站 HTTPS 地址。 */ val trustedUrl = urlPolicy.requireSiteUrl(detailsPageUrl)
        /** responseHtml 只在内存传给 parser。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml)
    }

    /** 按独立 BOOKS=ebooks 等路径读取最新资源。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.latestUrl(category))

    /** ALL 独立请求 newest；无 top 路径的类别不联网。 */
    override suspend fun getTopTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> {
        /** requestUrl 缺失表示固定上游没有该类别热门页。 */ val requestUrl = urlBuilder.topUrl(category) ?: return emptyList()
        return loadResults(requestUrl)
    }

    /** 校验内部 URL、执行网络并交给无网络列表 parser。 */
    private suspend fun loadResults(
        /** 构造器生成的列表页地址。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */ val trustedUrl = urlPolicy.requireSiteUrl(requestUrl)
        /** responseHtml 在调用协程获取。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** 固定站点与能力常量。 */
    private companion object {
        /** 固定上源实际站点根地址。 */ private const val BASE_URL = "https://developify.ca"
        /** 固定上源支持的七个类别。 */
        private val SUPPORTED_CATEGORIES = setOf(
            Category.APPS, Category.BOOKS, Category.GAMES, Category.MOVIES,
            Category.MUSIC, Category.PORN, Category.SERIES,
        )
    }
}
