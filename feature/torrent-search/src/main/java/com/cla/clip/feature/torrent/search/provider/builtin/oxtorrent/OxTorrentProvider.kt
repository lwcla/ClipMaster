package com.cla.clip.feature.torrent.search.provider.builtin.oxtorrent

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
import com.cla.clip.feature.torrent.search.provider.builtin.common.loadDetailDraftsInSourceOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 固定快照 OxTorrent Provider；入口只编排网络和详情补全。 */
internal class OxTorrentProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与映射计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定站点描述符。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("oxtorrent"), displayName = "OxTorrent",
        baseUrl = OxTorrentUrlBuilder.BASE_URL,
        supportedCategories = SUPPORTED_CATEGORIES,
        safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false,
    )
    /** 站点 URL 策略。 */
    private val urlPolicy = OxTorrentUrlPolicy()
    /** 无网络列表 parser。 */
    private val resultsParser = OxTorrentResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 无网络详情 parser。 */
    private val detailsParser = OxTorrentDetailsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 搜索读取列表后并发补详情。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 可选分类；不支持时执行无类别搜索。 */
        category: Category,
    ): List<Torrent> = load(OxTorrentUrlBuilder.searchUrl(query, category))
    /** 最新页不支持类别时直接空返回且不联网。 */
    override suspend fun getLatestTorrents(
        /** 浏览类别。 */
        category: Category,
    ): List<Torrent> = OxTorrentUrlBuilder.latestUrl(category)?.let {
        /** requestUrl 已按固定映射构造。 */
        requestUrl -> load(requestUrl)
    }.orEmpty()
    /** 热门页忽略类别。 */
    override suspend fun getTopTorrents(
        /** 固定上源忽略的浏览类别。 */
        category: Category,
    ): List<Torrent> = load(OxTorrentUrlBuilder.topUrl())
    /** 详情入口先校验地址再联网。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 拒绝恶意 authority。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** html 只在内存中传递。 */
        val html = networkClient.getText(trustedUrl)
        return detailsParser.parse(html, trustedUrl)
    }
    /** 解析全部合法草稿后，以结构化并发补全并保持顺序。 */
    private suspend fun load(
        /** 已构造列表地址。 */
        requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** html 网络读取留在调用协程。 */
        val html = networkClient.getText(trustedUrl)
        /** drafts 在 Default 完成校验，坏草稿不会触发详情请求。 */
        val drafts = resultsParser.parse(html, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已通过同站校验、等待补详情的 OxTorrent 草稿。
            loadDetails = { draft ->
                /** detailsHtml 由调用协程子任务读取。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml, draft.detailsPageUrl)
            },
            merge = resultsParser::merge,
        )
    }
    /** 固定支持类别集合。 */
    private companion object {
        /** 六类固定上源能力。 */
        private val SUPPORTED_CATEGORIES = setOf(Category.APPS, Category.BOOKS, Category.GAMES, Category.MOVIES, Category.MUSIC, Category.SERIES)
    }
}
