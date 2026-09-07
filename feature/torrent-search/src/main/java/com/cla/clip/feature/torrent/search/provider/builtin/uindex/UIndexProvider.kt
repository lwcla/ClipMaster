package com.cla.clip.feature.torrent.search.provider.builtin.uindex

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

/** UIndex 固定快照入口；网络留在调用协程，DOM 解析使用独立调度器。 */
internal class UIndexProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** 相对日期使用的可注入当前毫秒。 */ currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** DOM、日期与领域映射调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** manifest 审计确认该站含成人分类、受 Cloudflare 保护且默认关闭。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("uindex"),
        displayName = "UIndex",
        baseUrl = BASE_URL,
        cloudflareSolverUrl = "$BASE_URL/search.php?search=ubuntu",
        supportedCategories = SUPPORTED_CATEGORIES,
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
        isCloudflareProtected = true,
    )
    /** 站内 URL 输入与可选输出策略。 */ private val urlPolicy = UIndexUrlPolicy()
    /** 三种列表能力的结构化 URL 构造器。 */ private val urlBuilder = UIndexUrlBuilder()
    /** 列表 parser 持有注入时钟但不持有网络。 */ private val resultsParser = UIndexResultsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 详情 parser 不持有网络。 */ private val detailsParser = UIndexDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 按固定分类编号搜索。 */
    override suspend fun search(
        /** 用户查询。 */ query: String,
        /** 搜索分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.searchUrl(query, category))

    /** 网络前拒绝非审核 authority。 */
    override suspend fun getDetails(
        /** 外部详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是规范化后的同站 HTTPS 地址。 */ val trustedUrl = urlPolicy.requireSiteUrl(detailsPageUrl)
        /** responseHtml 只在内存传给 parser。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 按 search.php 无 search 参数读取最新资源。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.latestUrl(category))

    /** 按固定 24h top 页面读取热门资源。 */
    override suspend fun getTopTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.topUrl(category))

    /** 验证内部 URL、执行网络并交给无网络列表 parser。 */
    private suspend fun loadResults(
        /** 构造器生成的请求地址。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部 URL 构造漂移。 */ val trustedUrl = urlPolicy.requireSiteUrl(requestUrl)
        /** responseHtml 在调用协程中获取。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** 固定站点与能力常量。 */
    private companion object {
        /** 审核通过的根地址。 */ private const val BASE_URL = "https://uindex.org"
        /** 固定上源能标注的八个分类。 */
        private val SUPPORTED_CATEGORIES = setOf(
            Category.ANIME, Category.APPS, Category.GAMES, Category.MOVIES,
            Category.MUSIC, Category.PORN, Category.SERIES, Category.OTHER,
        )
    }
}
