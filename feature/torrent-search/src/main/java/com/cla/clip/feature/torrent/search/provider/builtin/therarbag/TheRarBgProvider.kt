package com.cla.clip.feature.torrent.search.provider.builtin.therarbag

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

/** TheRarBg 固定快照入口；列表解析后结构化并发补详情。 */
internal class TheRarBgProvider(
    /** 功能共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("therarbag"), displayName = "TheRarBg", baseUrl = TheRarBgUrlBuilder.BASE_URL,
        supportedCategories = Category.entries.toSet() - Category.ALL,
        safetyLevel = ProviderSafetyLevel.UNSAFE, enabledByDefault = false,
    )
    /** 站点所有输入输出只接受精确同站 HTTPS。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("therarbg.com"))
    /** 列表草稿 parser 与合并器。 */
    private val resultsParser = TheRarBgResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情标签 parser。 */
    private val detailsParser = TheRarBgDetailsParser(descriptor, parsingDispatcher)

    /** 搜索按固定关键词与可选分类路径读取。 */
    override suspend fun search(
        /** 原始查询只参与清洗和单路径段编码。 */ query: String,
        /** ALL 不附分类，其他九类使用固定映射。 */ category: Category,
    ): List<Torrent> = load(TheRarBgUrlBuilder.searchUrl(query, category))

    /** 外部详情地址在网络前校验。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是同站 HTTPS 详情地址。 */
        val trustedUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** detailsHtml 只在内存静态解析。 */
        val detailsHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(detailsHtml)
    }

    /** 最新列表使用固定两日窗口与可选分类。 */
    override suspend fun getLatestTorrents(
        /** ALL 不附分类。 */ category: Category,
    ): List<Torrent> = load(TheRarBgUrlBuilder.latestUrl(category))

    /** 热门列表使用固定两日窗口与做种降序。 */
    override suspend fun getTopTorrents(
        /** ALL 不附分类。 */ category: Category,
    ): List<Torrent> = load(TheRarBgUrlBuilder.topUrl(category))

    /** 读取列表、解析安全草稿并并发补全详情。 */
    private suspend fun load(
        /** URL builder 产出的列表页。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部路径构造漂移。 */
        val trustedUrl = urlPolicy.requireHttpsUrl(requestUrl).toString()
        /** listHtml 由共享网络上限保护。 */
        val listHtml = networkClient.getText(trustedUrl)
        /** drafts 已完成详情 URL 和列表字段解析。 */
        val drafts = resultsParser.parse(listHtml, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已通过同站校验、等待补详情的 TheRarBg 草稿。
            loadDetails = { draft ->
                /** detailsHtml 在结构化子任务中读取，失败与取消向上传播。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml)
            },
            merge = resultsParser::merge,
        )
    }
}
