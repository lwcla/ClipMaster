package com.cla.clip.feature.torrent.search.provider.builtin.subsplease

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** SubsPlease 固定快照入口；网络、URL 与无网络解析职责保持分离。 */
internal class SubsPleaseProvider(
    /** 功能共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** JSON、DOM 与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider {
    /** 固定上源与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("subsplease"), displayName = "SubsPlease", baseUrl = SubsPleaseUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.ANIME), safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** 所有请求和可选媒体只接受固定站点 HTTPS。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("subsplease.org"))
    /** 搜索与最新 JSON parser。 */
    private val resultsParser = SubsPleaseResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情 HTML 与 show JSON parser。 */
    private val detailsParser = SubsPleaseDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索请求使用结构化查询参数，响应在计算调度器解析。 */
    override suspend fun search(
        /** 原始查询只参与清洗和编码。 */ query: String,
        /** 固定上源忽略的调用方分类。 */ category: Category,
    ): List<Torrent> = loadResults(SubsPleaseUrlBuilder.searchUrl(query))

    /** 详情 URL 与 ep/res 在网络前校验，随后按 show ID 发起第二段 API 请求。 */
    override suspend fun getDetails(
        /** 不可信详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** request 固定同站 URL 和唯一剧集参数。 */
        val request = SubsPleaseUrlBuilder.parseDetailsRequest(detailsPageUrl, urlPolicy)
        /** pageHtml 只在内存中交给静态 DOM parser。 */
        val pageHtml = networkClient.getText(request.pageUrl)
        /** pageDraft 缺少 show ID 或标题时表示没有可用详情。 */
        val pageDraft = detailsParser.parsePage(pageHtml, request) ?: return null
        /** showUrl 只包含结构化 show ID 参数。 */
        val showUrl = SubsPleaseUrlBuilder.showUrl(pageDraft.showId)
        /** showJson 由网络大小边界限制，不进入日志。 */
        val showJson = networkClient.getJson(showUrl)
        return detailsParser.parseShow(showJson, pageDraft)
    }

    /** 最新列表忽略分类且每次独立读取固定 API。 */
    override suspend fun getLatestTorrents(
        /** 固定上源忽略的浏览分类。 */ category: Category,
    ): List<Torrent> = loadResults(SubsPleaseUrlBuilder.latestUrl())

    /** 校验内部构造 URL、读取 JSON 并完成无网络映射。 */
    private suspend fun loadResults(
        /** URL builder 产出的列表地址。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部构造漂移。 */
        val trustedUrl = urlPolicy.requireHttpsUrl(requestUrl).toString()
        /** responseJson 只在当前调用内存活。 */
        val responseJson = networkClient.getJson(trustedUrl)
        return resultsParser.parse(responseJson)
    }
}
