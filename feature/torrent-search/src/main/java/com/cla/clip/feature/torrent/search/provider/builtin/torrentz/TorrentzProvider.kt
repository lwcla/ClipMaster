package com.cla.clip.feature.torrent.search.provider.builtin.torrentz

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

/** Torrentz 固定快照入口；列表草稿通过结构化并发读取完整详情。 */
internal class TorrentzProvider(
    /** 功能共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM、日期与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("torrentz"), displayName = "Torrentz", baseUrl = TorrentzUrlBuilder.BASE_URL,
        supportedCategories = Category.entries.toSet() - Category.ALL,
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false,
    )
    /** 所有请求和可选输出只接受主站 HTTPS。 */
    private val siteUrlPolicy = BuiltinProviderUrlPolicy(setOf("torrentz2.nz"))
    /** 列表 parser 与合并器。 */
    private val resultsParser = TorrentzResultsParser(descriptor, siteUrlPolicy, parsingDispatcher)
    /** 详情 parser 不持有网络。 */
    private val detailsParser = TorrentzDetailsParser(descriptor, siteUrlPolicy, parsingDispatcher)

    /** 搜索按固定数字分类构造 query。 */
    override suspend fun search(
        /** 原始查询只参与清洗和编码。 */ query: String,
        /** 搜索分类。 */ category: Category,
    ): List<Torrent> = load(TorrentzUrlBuilder.searchUrl(query, category))

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

    /** 最新入口保留可选数字分类。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = load(TorrentzUrlBuilder.latestUrl(category))

    /** 热门入口保留可选数字分类。 */
    override suspend fun getTopTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = load(TorrentzUrlBuilder.topUrl(category))

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
            // draft 是已通过主站校验的 Torrentz 详情草稿。
            loadDetails = { draft ->
                /** detailsHtml 在结构化子任务读取，失败与取消向上传播。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml, draft.detailsPageUrl)
            },
            merge = resultsParser::merge,
        )
    }
}
