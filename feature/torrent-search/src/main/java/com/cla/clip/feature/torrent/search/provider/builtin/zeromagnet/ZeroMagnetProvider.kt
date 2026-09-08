package com.cla.clip.feature.torrent.search.provider.builtin.zeromagnet

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.loadDetailDraftsInSourceOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 固定快照 0Magnet Provider。 */
internal class ZeroMagnetProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与映射计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider {
    /** 固定 NSFW/default-off 描述符。 */
    override val descriptor = ProviderDescriptor(
        ProviderId("0magnet"), "0Magnet", ZeroMagnetUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.PORN),
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false,
    )
    /** URL 策略。 */
    private val urlPolicy = ZeroMagnetUrlPolicy()
    /** 独立列表 parser。 */
    private val resultsParser = ZeroMagnetResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 独立详情 parser。 */
    private val detailsParser = ZeroMagnetDetailsParser(descriptor, parsingDispatcher)
    /** 搜索列表并发补全详情。 */
    override suspend fun search(
        /** 原始搜索词。 */
        query: String,
        /** 固定上源忽略并始终产出成人类别。 */
        category: Category,
    ): List<Torrent> = load(ZeroMagnetUrlBuilder.searchUrl(query))
    /** 详情请求前严格拒绝恶意地址。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是同站 HTTPS 地址。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** html 网络读取留在调用协程。 */
        val html = networkClient.getText(trustedUrl)
        return detailsParser.parse(html)
    }
    /** 解析全部合法草稿后结构化并发补详情。 */
    private suspend fun load(
        /** 搜索请求地址。 */
        requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** html 在调用协程读取。 */
        val html = networkClient.getText(trustedUrl)
        /** drafts 在 Default 完成请求前 URL 校验。 */
        val drafts = resultsParser.parse(html, trustedUrl)
        return loadDetailDraftsInSourceOrder(
            drafts = drafts,
            // draft 是已通过同站校验、等待补详情的 0Magnet 草稿。
            loadDetails = { draft ->
                /** detailsHtml 由子任务并发读取。 */
                val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                detailsParser.parse(detailsHtml)
            },
            merge = resultsParser::merge,
        )
    }
}
