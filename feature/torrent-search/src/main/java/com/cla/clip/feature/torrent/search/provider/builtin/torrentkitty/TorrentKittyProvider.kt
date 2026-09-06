package com.cla.clip.feature.torrent.search.provider.builtin.torrentkitty

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

/** TorrentKitty 固定快照 Provider；入口只编排同站网络与无网络 parser。 */
internal class TorrentKittyProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider {
    /** manifest 固定元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("torrentkitty"),
        displayName = "TorrentKitty",
        baseUrl = TorrentKittyUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.OTHER),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )

    /** 同站请求与产出 URL 策略。 */
    private val urlPolicy = TorrentKittyUrlPolicy()

    /** 固定搜索 URL 构造器。 */
    private val urlBuilder = TorrentKittyUrlBuilder()

    /** 列表 DOM parser。 */
    private val resultsParser = TorrentKittyResultsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 详情 DOM parser。 */
    private val detailsParser = TorrentKittyDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索按单个编码 path segment 发起一次请求。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 调用方分类。 */
        category: Category,
    ): List<Torrent> {
        /** requestUrl 是结构化生成的固定搜索地址。 */
        val requestUrl = urlBuilder.searchUrl(query)
        /** trustedUrl 防止内部常量或 builder 漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** 详情 URL 在网络前校验，响应解析进入计算调度器。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是精确同站 HTTPS 地址。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }
}
