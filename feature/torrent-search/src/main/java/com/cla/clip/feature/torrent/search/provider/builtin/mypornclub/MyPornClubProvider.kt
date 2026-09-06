package com.cla.clip.feature.torrent.search.provider.builtin.mypornclub

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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 固定快照 MyPornClub Provider；入口负责同站网络与详情补全。 */
internal class MyPornClubProvider(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期解析使用的可注入时钟。 */
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** DOM 与领域映射使用的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定站点描述符。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("mypornclub"),
        displayName = "MyPornClub",
        baseUrl = MyPornClubUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.PORN),
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
    )

    /** 精确站点 URL 边界。 */
    private val urlPolicy = MyPornClubUrlPolicy()

    /** 固定路径构造器。 */
    private val urlBuilder = MyPornClubUrlBuilder()

    /** 无网络列表 parser。 */
    private val resultsParser = MyPornClubResultsParser(
        descriptor = descriptor,
        urlPolicy = urlPolicy,
        currentTimeMillis = currentTimeMillis,
        parsingDispatcher = parsingDispatcher,
    )

    /** 无网络详情 parser。 */
    private val detailsParser = MyPornClubDetailsParser(
        descriptor = descriptor,
        urlPolicy = urlPolicy,
        currentTimeMillis = currentTimeMillis,
        parsingDispatcher = parsingDispatcher,
    )

    /** 搜索按固定路径读取并补全详情。 */
    override suspend fun search(
        /** 原始查询。 */
        query: String,
        /** 固定上源只支持成人分类。 */
        category: Category,
    ): List<Torrent> = load(urlBuilder.searchUrl(query))

    /** 最新列表每次独立请求。 */
    override suspend fun getLatestTorrents(
        /** 固定上源忽略浏览分类。 */
        category: Category,
    ): List<Torrent> = load(urlBuilder.latestUrl())

    /** 热门列表每次独立请求。 */
    override suspend fun getTopTorrents(
        /** 固定上源忽略浏览分类。 */
        category: Category,
    ): List<Torrent> = load(urlBuilder.topUrl())

    /** 详情入口先校验地址，再在调用协程读取。 */
    override suspend fun getDetails(
        /** 不可信详情地址。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 只允许固定 host HTTPS 默认端口。 */
        val trustedUrl = urlPolicy.requireUrl(detailsPageUrl)
        /** responseHtml 只在内存中传递给 parser。 */
        val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 读取列表、生成安全 draft，再结构化并发补全详情。 */
    private suspend fun load(
        /** 已结构化生成的固定同站 URL。 */
        requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部 URL 构造漂移。 */
        val trustedUrl = urlPolicy.requireUrl(requestUrl)
        /** responseHtml 在调用协程读取。 */
        val responseHtml = networkClient.getText(trustedUrl)
        /** drafts 已在计算调度器完成必填和 URL 校验。 */
        val drafts = resultsParser.parse(responseHtml, trustedUrl)
        return coroutineScope {
            /** deferredDetails 按 draft 顺序持有子任务，awaitAll 保持输出顺序。 */
            val deferredDetails = drafts.map { draft ->
                async {
                    /** detailsHtml 网络调用保留在调用协程。 */
                    val detailsHtml = networkClient.getText(draft.detailsPageUrl)
                    /** details 缺必填时只跳过当前结果。 */
                    val details = detailsParser.parse(detailsHtml, draft.detailsPageUrl)
                        ?: return@async null
                    resultsParser.merge(draft, details)
                }
            }
            deferredDetails.awaitAll().filterNotNull()
        }
    }
}
