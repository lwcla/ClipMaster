package com.cla.clip.feature.torrent.search.provider.builtin.mikan

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 固定快照 Mikan Provider；入口只编排同站网络。 */
internal class MikanProvider(
    /** 功能共享网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider {
    /** 固定站点元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("mikanproject"),
        displayName = "Mikan",
        baseUrl = MikanUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )

    /** 唯一站点 URL 边界。 */
    private val urlPolicy = MikanUrlPolicy()

    /** 固定请求 URL 构造器。 */
    private val urlBuilder = MikanUrlBuilder()

    /** 列表 parser 不持有网络。 */
    private val resultsParser = MikanResultsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 详情 parser 不持有网络。 */
    private val detailsParser = MikanDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索使用结构化 query 参数。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 固定上源只支持动漫分类。 */
        category: Category,
    ): List<Torrent> {
        /** requestUrl 由 HttpUrl 负责查询编码。 */
        val requestUrl = urlBuilder.searchUrl(query)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(requestUrl)
        return resultsParser.parse(responseHtml, requestUrl)
    }

    /** 详情入口在网络前校验 URL。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 仅允许固定 host HTTPS 默认端口。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }
}
