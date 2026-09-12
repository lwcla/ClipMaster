package com.cla.clip.feature.torrent.search.provider.builtin.xxxclub

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

/** XXXClub 固定快照入口；列表草稿通过结构化并发补充详情身份。 */
internal class XXXClubProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM、日期与领域映射计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("xxxclub"),
        displayName = "XXXClub",
        baseUrl = XXXClubUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.PORN),
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
    )
    /** 所有请求和可选媒体只接受主站 HTTPS。 */
    private val siteUrlPolicy = BuiltinProviderUrlPolicy(setOf("xxxclub.to"))
    /** 列表 parser 与详情合并器。 */
    private val resultsParser = XXXClubResultsParser(descriptor, siteUrlPolicy, parsingDispatcher)
    /** 详情 parser 不持有网络。 */
    private val detailsParser = XXXClubDetailsParser(descriptor, siteUrlPolicy, parsingDispatcher)

    /** 搜索将清洗后的查询编码为单个路径段。 */
    override suspend fun search(
        /** 原始查询只参与控制字符清洗和路径编码。 */ query: String,
        /** 固定来源只支持成人分类并忽略此筛选值。 */ category: Category,
    ): List<Torrent> = load(XXXClubUrlBuilder.searchUrl(query))

    /** 外部详情地址在网络前通过精确主站 policy。 */
    override suspend fun getDetails(
        /** 不可信详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是精确同站 HTTPS 地址。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** detailsHtml 只在内存静态解析。 */
        val detailsHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(detailsHtml, trustedUrl)
    }

    /** 最新入口忽略分类且每次独立请求。 */
    override suspend fun getLatestTorrents(
        /** 固定来源忽略的分类。 */ category: Category,
    ): List<Torrent> = load(XXXClubUrlBuilder.latestUrl())

    /** 热门入口忽略分类且每次独立请求。 */
    override suspend fun getTopTorrents(
        /** 固定来源忽略的分类。 */ category: Category,
    ): List<Torrent> = load(XXXClubUrlBuilder.topUrl())

    /** 读取列表、解析安全草稿并结构化并发补详情。 */
    private suspend fun load(
        /** URL builder 产出的列表页地址。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部路径构造漂移。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(requestUrl).toString()
        /** listHtml 受统一 5 MiB 网络边界保护。 */
        val listHtml = networkClient.getText(trustedUrl)
        /** drafts 在计算调度器完成必填字段与详情 URL 校验。 */
        val drafts = resultsParser.parse(listHtml, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已通过主站校验、等待补详情的 XXXClub 草稿。
            loadDetails = { draft ->
                /** detailsHtml 在结构化子任务读取，失败与取消向上传播。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml, draft.detailsPageUrl)
            },
            merge = resultsParser::merge,
        )
    }
}
