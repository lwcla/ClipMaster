package com.cla.clip.feature.torrent.search.provider.builtin.dmhy

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Dmhy 四种读取能力的请求编排；没有后台观察或持久状态。 */
internal class DmhyProvider(
    /** 所有请求共用可取消的网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 响应 DOM 与领域映射的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 与固定快照一致的能力元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("dmhy"), displayName = "Dmhy", baseUrl = "https://share.dmhy.org",
        supportedCategories = setOf(Category.ANIME, Category.BOOKS, Category.GAMES, Category.MUSIC, Category.SERIES, Category.OTHER),
        safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false,
    )
    /** 仅允许已审计的精确站点。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("share.dmhy.org"))
    /** 解析协作者不持有网络。 */
    private val parser = DmhyHtmlParser(descriptor, urlPolicy, parsingDispatcher)

    /** 固定查询参数由 HttpUrl 编码，不支持分类回退站点全部类别。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 查询、分类与固定排序字段依上源顺序编码。 */
        val url = descriptor.baseUrl.toHttpUrl().newBuilder().addPathSegments("topics/list")
            .addQueryParameter("keyword", sanitizeProviderQuery(query))
            .addQueryParameter("sort_id", DmhyCategoryMapper.requestId(category))
            .addQueryParameter("team_id", "0").addQueryParameter("order", "date-desc").build().toString()
        return readResults(url)
    }

    /** 每次浏览独立发起请求，不缓存到另一能力调用。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> {
        /** 固定路径与类别 ID 均结构化构建。 */
        val url = descriptor.baseUrl.toHttpUrl().newBuilder().addPathSegments("topics/list/sort_id")
            .addPathSegment(DmhyCategoryMapper.requestId(category)).build().toString()
        return readResults(url)
    }

    /** 固定上源 top 使用最新列表，但每次调用仍独立读取。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> = getLatestTorrents(category)

    /** 详情输入先验证同站 HTTPS，之后才交网络边界。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 已验证地址用于网络与相对分类链接解析。 */
        val url = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 有上限的原始 HTML 不进入日志。 */
        val html = networkClient.getText(url)
        return parser.parseDetails(html, url)
    }

    /** 网络留调用协程，DOM 解析由协作者切换计算调度器。 */
    private suspend fun readResults(url: String): List<Torrent> {
        /** 同站搜索响应仅供本次解析。 */
        val html = networkClient.getText(url)
        return parser.parseResults(html, url)
    }
}
