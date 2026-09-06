package com.cla.clip.feature.torrent.search.provider.builtin.megapeer

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 固定快照 MegaPeer Provider；入口负责网络和结构化详情补全。 */
internal class MegaPeerProvider(
    /** 功能共享网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider {
    /** 固定站点元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("megapeer"),
        displayName = "MegaPeer",
        baseUrl = MegaPeerUrlBuilder.BASE_URL,
        supportedCategories = setOf(
            Category.APPS,
            Category.BOOKS,
            Category.GAMES,
            Category.MOVIES,
            Category.MUSIC,
            Category.OTHER,
            Category.SERIES,
        ),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )

    /** 同站 URL 边界。 */
    private val urlPolicy = MegaPeerUrlPolicy()

    /** 固定搜索 URL 构造器。 */
    private val urlBuilder = MegaPeerUrlBuilder()

    /** 无网络列表 parser。 */
    private val resultsParser = MegaPeerResultsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 无网络详情 parser。 */
    private val detailsParser = MegaPeerDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索先解析 draft，再并发读取详情并按 draft 顺序合并。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 搜索分类。 */
        category: Category,
    ): List<Torrent> {
        /** requestUrl 使用 HttpUrl 编码全部固定参数。 */
        val requestUrl = urlBuilder.searchUrl(query, category)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(requestUrl)
        /** drafts 已完成必填字段与详情 URL 校验。 */
        val drafts = resultsParser.parse(responseHtml, requestUrl)
        return coroutineScope {
            /** deferredResults 按 draft 顺序持有详情子任务。 */
            val deferredResults = drafts.map { draft ->
                async {
                    /** detailsHtml 网络读取留在调用协程。 */
                    val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                    /** details 只为列表补充身份字段。 */
                    val details = detailsParser.parse(detailsHtml, draft.detailsPageUrl)
                        ?: return@async null
                    resultsParser.merge(draft, details.infoHash)
                }
            }
            deferredResults.awaitAll().filterNotNull()
        }
    }

    /** 详情 URL 在网络前执行精确站点校验。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 只允许固定站点 HTTPS 默认端口。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }
}
