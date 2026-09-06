package com.cla.clip.feature.torrent.search.provider.builtin.sukebei

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.*
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import com.cla.clip.feature.torrent.search.provider.builtin.nyaafamily.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 固定快照 Sukebei Provider；安全元数据与请求协议保持独立。 */
internal class SukebeiProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定 NSFW、PORN 与默认关闭元数据。 */
    override val descriptor = ProviderDescriptor(
        ProviderId("sukebeinyaa"), "Sukebei", BASE_URL, supportedCategories = setOf(Category.PORN),
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false,
    )
    /** 唯一精确 host 边界。 */ private val urlPolicy = BuiltinProviderUrlPolicy(setOf(HOST))
    /** 页面类别不可信，固定映射 PORN。 */ private val categoryMapper = NyaaFamilyCategoryMapper { Category.PORN }
    /** 共享列表 parser。 */ private val resultsParser = NyaaFamilyResultsParser(descriptor, urlPolicy, categoryMapper, parsingDispatcher)
    /** Sukebei 特有详情卡 parser。 */ private val detailsParser = NyaaFamilyDetailsParser(descriptor, urlPolicy, categoryMapper, "div.container > div.panel", parsingDispatcher)

    /** 搜索固定发送 f/c/q。 */
    override suspend fun search(query: String, category: Category): List<Torrent> =
        load(baseBuilder().addQueryParameter("f", "0").addQueryParameter("c", "0_0").addQueryParameter("q", sanitizeProviderQuery(query)).build())

    /** 详情输入在网络前精确校验。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** trustedUrl 只允许本源标准 HTTPS。 */ val trustedUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** responseHtml 只在内存中传入 parser。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 最新列表使用无参数根 URL。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> = load(baseBuilder().build())

    /** 热门列表只发送排序参数。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> =
        load(baseBuilder().addQueryParameter("s", "seeders").addQueryParameter("o", "desc").build())

    /** 网络留在调用协程，响应后才进入 parser dispatcher。 */
    private suspend fun load(/** 已结构化编码的请求 URL。 */ requestUrl: HttpUrl): List<Torrent> {
        /** trustedUrl 防止内部常量漂移。 */ val trustedUrl = urlPolicy.requireHttpsUrl(requestUrl.toString()).toString()
        /** responseHtml 是受网络层大小限制的文本。 */ val responseHtml = networkClient.getText(trustedUrl)
        return resultsParser.parse(responseHtml, trustedUrl)
    }

    /** 返回固定根路径 builder。 */
    private fun baseBuilder(): HttpUrl.Builder = BASE_URL.toHttpUrl().newBuilder()

    /** 固定来源常量。 */
    private companion object {
        /** 唯一允许 host。 */ private const val HOST = "sukebei.nyaa.si"
        /** 站点根地址。 */ private const val BASE_URL = "https://sukebei.nyaa.si/"
    }
}
