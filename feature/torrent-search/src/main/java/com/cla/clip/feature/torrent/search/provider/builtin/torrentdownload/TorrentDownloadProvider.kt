package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownload

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
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** TorrentDownload 固定快照入口；列表可离线提取 hash，不产生逐条详情请求。 */
internal class TorrentDownloadProvider(
    /** 功能共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** 列表相对日期使用的可注入时钟。 */ currentEpochMillis: () -> Long,
    /** DOM、日期与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("torrentdownloadinfo"), displayName = "TorrentDownload",
        baseUrl = TorrentDownloadUrlBuilder.BASE_URL,
        supportedCategories = Category.entries.toSet() - Category.ALL,
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false,
    )
    /** 列表和详情输入只接受主站 HTTPS。 */
    private val siteUrlPolicy = BuiltinProviderUrlPolicy(setOf("torrentdownload.info"))
    /** 可选 torrent 文件只接受 manifest 中的 itorrent.net。 */
    private val fileUrlPolicy = BuiltinProviderUrlPolicy(setOf("itorrent.net"))
    /** 列表 parser 不持有网络。 */
    private val resultsParser = TorrentDownloadResultsParser(
        descriptor, siteUrlPolicy, currentEpochMillis, parsingDispatcher,
    )
    /** 详情 parser 不持有网络。 */
    private val detailsParser = TorrentDownloadDetailsParser(descriptor, fileUrlPolicy, parsingDispatcher)

    /** 搜索忽略分类并读取固定 search 端点。 */
    override suspend fun search(
        /** 原始查询只参与清洗和编码。 */ query: String,
        /** 固定上游忽略的分类。 */ category: Category,
    ): List<Torrent> = load(TorrentDownloadUrlBuilder.searchUrl(query))

    /** 外部详情地址必须在网络前通过精确主站策略。 */
    override suspend fun getDetails(
        /** 不可信详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是规范化后的同站 HTTPS 地址。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** detailsHtml 只在内存传给无网络 parser。 */
        val detailsHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(detailsHtml)
    }

    /** 最新入口忽略分类且每次独立请求。 */
    override suspend fun getLatestTorrents(
        /** 固定上游忽略的分类。 */ category: Category,
    ): List<Torrent> = load(TorrentDownloadUrlBuilder.latestUrl())

    /** 热门入口忽略分类且每次独立请求。 */
    override suspend fun getTopTorrents(
        /** 固定上游忽略的分类。 */ category: Category,
    ): List<Torrent> = load(TorrentDownloadUrlBuilder.topUrl())

    /** 校验内部 URL、执行网络并解析单页列表。 */
    private suspend fun load(
        /** URL builder 产出的列表页。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(requestUrl).toString()
        /** listHtml 受统一 5 MiB 响应边界保护。 */
        val listHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(listHtml, trustedUrl)
    }
}
