package com.cla.clip.feature.torrent.search.provider.builtin.linuxtracker

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

/** 固定快照 LinuxTracker Provider；入口只编排同站网络。 */
internal class LinuxTrackerProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM、日期和领域映射使用的计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** LinuxTracker 的 SAFE、APPS 与默认关闭元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("linuxtracker"), displayName = "LinuxTracker", baseUrl = BASE_URL,
        supportedCategories = setOf(Category.APPS), safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false,
    )
    /** 唯一站点 URL 边界。 */ private val urlPolicy = LinuxTrackerUrlPolicy()
    /** 结构化请求 URL 构造器。 */ private val urlBuilder = LinuxTrackerUrlBuilder()
    /** 列表 parser 不持有网络。 */ private val resultsParser = LinuxTrackerResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情 parser 不持有网络。 */ private val detailsParser = LinuxTrackerDetailsParser(descriptor, parsingDispatcher)

    /** 搜索按固定 query 参数结构请求。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = load(urlBuilder.searchUrl(query))

    /** 详情 URL 网络前精确校验。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** trustedUrl 是同站 HTTPS 默认端口地址。 */ val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** responseHtml 留在内存。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 最新入口每次独立请求空 search URL。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> = load(urlBuilder.latestUrl())

    /** 热门能力发起新的 latest 形状请求，不共享缓存。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> = load(urlBuilder.latestUrl())

    /** 获取列表 HTML 后切入无网络 parser。 */
    private suspend fun load(/** 已结构化生成的同站 URL。 */ requestUrl: String): List<Torrent> {
        /** trustedUrl 防止内部常量漂移。 */ val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** responseHtml 在调用协程读取。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** LinuxTracker 固定主页。 */
    private companion object { /** 站点根地址。 */ private const val BASE_URL = "https://linuxtracker.org" }
}
