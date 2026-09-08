package com.cla.clip.feature.torrent.search.provider.builtin.thirteenthirtysevenx

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.loadDetailDraftsInSourceOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 1337x 固定快照入口；列表草稿通过结构化并发补充详情身份。 */
internal class ThirteenThirtySevenXProvider(
    /** 功能共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期与当日时间使用的可注入时钟。 */ currentEpochMillis: () -> Long,
    /** DOM、日期与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, TopTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("1337x"), displayName = "1337x", baseUrl = ThirteenThirtySevenXUrlBuilder.BASE_URL,
        cloudflareSolverUrl = ThirteenThirtySevenXUrlBuilder.BASE_URL,
        supportedCategories = SUPPORTED_CATEGORIES,
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false, isCloudflareProtected = true,
    )
    /** 详情与海报只接受主站 HTTPS。 */
    private val siteUrlPolicy = BuiltinProviderUrlPolicy(setOf("1337x.to"))
    /** torrent 文件只接受清单中的 itorrents.org。 */
    private val fileUrlPolicy = BuiltinProviderUrlPolicy(setOf("itorrents.org"))
    /** 列表草稿 parser 与合并器。 */
    private val resultsParser = ThirteenThirtySevenXResultsParser(
        descriptor, siteUrlPolicy, currentEpochMillis, parsingDispatcher,
    )
    /** 详情 DOM parser。 */
    private val detailsParser = ThirteenThirtySevenXDetailsParser(
        descriptor, siteUrlPolicy, fileUrlPolicy, currentEpochMillis, parsingDispatcher,
    )

    /** 搜索根据支持分类构造固定路径，不支持类别安全回退全部搜索。 */
    override suspend fun search(
        /** 原始查询只参与清洗和编码。 */ query: String,
        /** 支持类别进入 category-search。 */ category: Category,
    ): List<Torrent> = load(ThirteenThirtySevenXUrlBuilder.searchUrl(query, category))

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

    /** 热门页忽略分类且每次独立请求。 */
    override suspend fun getTopTorrents(
        /** 固定上源忽略的浏览分类。 */ category: Category,
    ): List<Torrent> = load(ThirteenThirtySevenXUrlBuilder.topUrl())

    /** 读取列表、解析安全草稿并并发补全详情。 */
    private suspend fun load(
        /** URL builder 产出的列表页。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = siteUrlPolicy.requireHttpsUrl(requestUrl).toString()
        /** listHtml 受统一 5 MiB 边界保护。 */
        val listHtml = networkClient.getText(trustedUrl)
        /** drafts 在计算调度器完成字段与 URL 校验。 */
        val drafts = resultsParser.parse(listHtml, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已通过主站校验、等待补详情的 1337x 草稿。
            loadDetails = { draft ->
                /** detailsHtml 在结构化子任务读取，失败与取消向上传播。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml, draft.detailsPageUrl)
            },
            merge = resultsParser::merge,
        )
    }

    /** 固定支持类别集合。 */
    private companion object {
        /** 除 ALL 与 BOOKS 外的八类上源能力。 */
        private val SUPPORTED_CATEGORIES = Category.entries.toSet() - setOf(Category.ALL, Category.BOOKS)
    }
}
