package com.cla.clip.feature.torrent.search.provider.builtin.torrentdownloads

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
import com.cla.clip.feature.torrent.search.provider.builtin.common.loadDetailDraftsInSourceOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** TorrentDownloads 固定快照入口；列表草稿通过结构化并发补充详情。 */
internal class TorrentDownloadsProvider(
    /** 功能共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM、日期与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("torrentdownloads"), displayName = "TorrentDownloads",
        baseUrl = TorrentDownloadsUrlBuilder.BASE_URL,
        cloudflareSolverUrl = "https://torrentdownloads.pro/search/?s_cat=0&search=ubuntu",
        supportedCategories = setOf(
            Category.ANIME, Category.APPS, Category.BOOKS, Category.GAMES,
            Category.MOVIES, Category.MUSIC, Category.SERIES, Category.OTHER,
        ),
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false, isCloudflareProtected = true,
    )
    /** 所有请求和可选下载地址只接受主站 HTTPS。 */
    private val siteUrlPolicy = BuiltinProviderUrlPolicy(setOf("torrentdownloads.pro"))
    /** 列表 parser 与合并器。 */
    private val resultsParser = TorrentDownloadsResultsParser(descriptor, siteUrlPolicy, parsingDispatcher)
    /** 详情 parser 不持有网络。 */
    private val detailsParser = TorrentDownloadsDetailsParser(descriptor, siteUrlPolicy, parsingDispatcher)

    /** 搜索使用数字分类和结构化查询。 */
    override suspend fun search(
        /** 原始查询只参与清洗和编码。 */ query: String,
        /** 搜索分类映射为固定数字 ID。 */ category: Category,
    ): List<Torrent> = load(TorrentDownloadsUrlBuilder.searchUrl(query, category))

    /** 外部详情地址在网络前通过主站 policy。 */
    override suspend fun getDetails(
        /** 不可信详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是精确同站 HTTPS 地址。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** detailsHtml 只在内存静态解析。 */
        val detailsHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(detailsHtml, trustedUrl)
    }

    /** 最新入口按类别选择 today 页面或固定回退。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = load(TorrentDownloadsUrlBuilder.latestUrl(category))

    /** 热门入口按类别选择 popular 页面或固定回退。 */
    override suspend fun getTopTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = load(TorrentDownloadsUrlBuilder.topUrl(category))

    /** 读取列表、解析安全草稿并并发补详情。 */
    private suspend fun load(
        /** URL builder 产出的列表页。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(requestUrl).toString()
        /** listHtml 受统一 5 MiB 边界保护。 */
        val listHtml = networkClient.getText(trustedUrl)
        /** drafts 在计算调度器完成详情 URL 校验。 */
        val drafts = resultsParser.parse(listHtml, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已验证主站详情 URL 和列表回退字段。
            loadDetails = { draft ->
                /** detailsHtml 在结构化子任务读取，失败与取消向上传播。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml, draft.detailsPageUrl)
            },
            merge = resultsParser::merge,
        )
    }
}
