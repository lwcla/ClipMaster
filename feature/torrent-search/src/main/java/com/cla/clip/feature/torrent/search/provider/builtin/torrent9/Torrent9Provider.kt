package com.cla.clip.feature.torrent.search.provider.builtin.torrent9

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

/** 固定快照 Torrent9 Provider。 */
internal class Torrent9Provider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与映射计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定描述符。 */
    override val descriptor = ProviderDescriptor(ProviderId("torrent9"), "Torrent9", Torrent9UrlBuilder.BASE_URL, supportedCategories = SUPPORTED_CATEGORIES, safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false)
    /** URL 策略。 */
    private val urlPolicy = Torrent9UrlPolicy()
    /** 独立列表 parser。 */
    private val resultsParser = Torrent9ResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 独立详情 parser。 */
    private val detailsParser = Torrent9DetailsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 搜索列表并发补详情。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 可选分类。 */
        category: Category,
    ): List<Torrent> = load(Torrent9UrlBuilder.searchUrl(query, category))
    /** 不支持分类时空返回且不联网。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类。 */
        category: Category,
    ): List<Torrent> = Torrent9UrlBuilder.latestUrl(category)?.let {
        /** requestUrl 是固定映射地址。 */
        requestUrl -> load(requestUrl)
    }.orEmpty()
    /** 热门页忽略分类。 */
    override suspend fun getTopTorrents(
        /** 上源忽略的分类。 */
        category: Category,
    ): List<Torrent> = load(Torrent9UrlBuilder.topUrl())
    /** 详情请求前校验同站地址。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是校验后的地址。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** html 在调用协程读取。 */
        val html = networkClient.getText(trustedUrl)
        return detailsParser.parse(html, trustedUrl)
    }
    /** 先解析所有草稿，再结构化并发补全详情。 */
    private suspend fun load(
        /** 列表请求地址。 */
        requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** html 网络读取留在调用协程。 */
        val html = networkClient.getText(trustedUrl)
        /** drafts 已在 Default 完成必填校验。 */
        val drafts = resultsParser.parse(html, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已通过同站校验、等待补详情的 Torrent9 草稿。
            loadDetails = { draft ->
                /** detailsHtml 由子任务并发读取。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml, draft.detailsPageUrl)
            },
            merge = resultsParser::merge,
        )
    }
    /** 固定能力集合。 */
    private companion object {
        /** 六个法语站点类别。 */
        private val SUPPORTED_CATEGORIES = setOf(Category.APPS, Category.BOOKS, Category.GAMES, Category.MOVIES, Category.MUSIC, Category.SERIES)
    }
}
