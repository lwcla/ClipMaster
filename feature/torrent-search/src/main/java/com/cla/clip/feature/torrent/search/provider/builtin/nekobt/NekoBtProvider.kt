package com.cla.clip.feature.torrent.search.provider.builtin.nekobt

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.*
import com.cla.clip.feature.torrent.search.provider.builtin.common.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 固定快照 NekoBT Provider；继续使用审计通过的 HTML 接口。 */
internal class NekoBtProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定 SAFE、ANIME 与默认关闭元数据。 */
    override val descriptor = ProviderDescriptor(
        ProviderId("nekobt"), "NekoBT", BASE_URL, supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = false,
    )
    /** 唯一 host 的精确 URL 边界。 */ private val urlPolicy = BuiltinProviderUrlPolicy(setOf(HOST))
    /** 独立列表 parser。 */ private val resultsParser = NekoBtResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 独立详情 parser。 */ private val detailsParser = NekoBtDetailsParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索使用固定 query 参数并由 HttpUrl 编码。 */
    override suspend fun search(query: String, category: Category): List<Torrent> =
        load(baseBuilder().addPathSegment("search").addQueryParameter("query", sanitizeProviderQuery(query)).build())

    /** 详情地址必须在联网前通过同站策略。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** trustedUrl 是标准 HTTPS 同站地址。 */ val trustedUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** responseHtml 只在内存中解析。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 最新列表保留带连字符 sort-by。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> =
        load(baseBuilder().addPathSegment("search").addQueryParameter("sort-by", "latest").build())

    /** 热门列表保留带连字符 sort-by。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> =
        load(baseBuilder().addPathSegment("search").addQueryParameter("sort-by", "seeders").build())

    /** 网络留在调用协程，响应后进入 parser dispatcher。 */
    private suspend fun load(/** 已结构化编码的请求 URL。 */ requestUrl: HttpUrl): List<Torrent> {
        /** trustedUrl 防止内部常量漂移。 */ val trustedUrl = urlPolicy.requireHttpsUrl(requestUrl.toString()).toString()
        /** responseHtml 是受网络层大小限制的文本。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** 返回不带尾斜杠重复风险的根 URL builder。 */
    private fun baseBuilder(): HttpUrl.Builder = BASE_URL.toHttpUrl().newBuilder()

    /** 固定来源常量。 */
    private companion object {
        /** 唯一允许 host。 */ private const val HOST = "nekobt.to"
        /** 站点根地址。 */ private const val BASE_URL = "https://nekobt.to"
    }
}
