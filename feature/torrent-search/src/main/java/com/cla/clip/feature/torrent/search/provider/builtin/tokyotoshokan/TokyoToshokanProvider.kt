package com.cla.clip.feature.torrent.search.provider.builtin.tokyotoshokan

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

/** TokyoToshokan 固定快照入口；网络留在调用协程，DOM 解析交给独立协作者。 */
internal class TokyoToshokanProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM、日期与领域映射使用的计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** manifest 审计确认该站含成人分类，因此默认关闭。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("tokyotoshokan"),
        displayName = "TokyoToshokan",
        baseUrl = BASE_URL,
        supportedCategories = SUPPORTED_CATEGORIES,
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
    )
    /** 搜索、浏览与详情输入共用的精确地址策略。 */ private val urlPolicy = TokyoToshokanUrlPolicy()
    /** 结构化查询地址构造器。 */ private val urlBuilder = TokyoToshokanUrlBuilder()
    /** 双行列表解析器不持有网络能力。 */ private val resultsParser = TokyoToshokanResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情解析器不持有网络能力。 */ private val detailsParser = TokyoToshokanDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 按完整十类矩阵搜索，未原生支持的类别回退 type=0。 */
    override suspend fun search(
        /** 用户查询，控制字符由构造器清洗。 */ query: String,
        /** 搜索分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.searchUrl(query, category))

    /** 在发起网络前拒绝非审核 authority。 */
    override suspend fun getDetails(
        /** 外部传入的详情页地址。 */ detailsPageUrl: String,
    ): TorrentDetails? {
        /** trustedUrl 是规范化后的同站 HTTPS 地址。 */ val trustedUrl = urlPolicy.requireSiteUrl(detailsPageUrl)
        /** responseHtml 只在内存中传给无网络 parser。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 每次独立读取首页形状；无原生映射的分类省略 cat。 */
    override suspend fun getLatestTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.latestUrl(category))

    /** 固定上源热门能力等同独立最新请求，不共享缓存。 */
    override suspend fun getTopTorrents(
        /** 浏览分类。 */ category: Category,
    ): List<Torrent> = loadResults(urlBuilder.latestUrl(category))

    /** 校验内部地址、执行请求并交给列表 parser。 */
    private suspend fun loadResults(
        /** 构造器生成的列表页地址。 */ requestUrl: String,
    ): List<Torrent> {
        /** trustedUrl 防止内部 URL 构造漂移。 */ val trustedUrl = urlPolicy.requireSiteUrl(requestUrl)
        /** responseHtml 在调用协程中获取。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** 固定站点与能力常量。 */
    private companion object {
        /** 审核通过的站点根地址。 */ private const val BASE_URL = "https://tokyotosho.info"
        /** 固定上源能标注的五个分类。 */
        private val SUPPORTED_CATEGORIES = setOf(Category.ANIME, Category.BOOKS, Category.MUSIC, Category.PORN, Category.OTHER)
    }
}
