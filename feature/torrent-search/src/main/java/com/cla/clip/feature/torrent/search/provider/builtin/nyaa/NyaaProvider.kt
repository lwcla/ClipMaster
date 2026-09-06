package com.cla.clip.feature.torrent.search.provider.builtin.nyaa

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

/** 固定快照 Nyaa Provider；入口只编排同站网络。 */
internal class NyaaProvider(
    /** 功能内共享网络客户端。 */ private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射计算调度器。 */ parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** 固定 SAFE、六分类与默认开启元数据。 */
    override val descriptor = ProviderDescriptor(
        ProviderId("nyaasi"), "Nyaa", BASE_URL, supportedCategories = setOf(Category.ANIME, Category.APPS, Category.BOOKS, Category.GAMES, Category.MUSIC, Category.SERIES),
        safetyLevel = ProviderSafetyLevel.SAFE, enabledByDefault = true,
    )
    /** 备用详情域只暴露已审计 host。 */ override val alternateUrlDomains = listOf(MIRROR_HOST)
    /** 两个已审计 host 的精确边界。 */ private val urlPolicy = BuiltinProviderUrlPolicy(setOf(PRIMARY_HOST, MIRROR_HOST))
    /** 完整类别映射策略。 */ private val categoryMapper = NyaaFamilyCategoryMapper(::categoryFromId)
    /** 共享列表 parser。 */ private val resultsParser = NyaaFamilyResultsParser(descriptor, urlPolicy, categoryMapper, parsingDispatcher)
    /** Nyaa 特有详情卡 parser。 */ private val detailsParser = NyaaFamilyDetailsParser(descriptor, urlPolicy, categoryMapper, ".container > div:nth-child(1)", parsingDispatcher)

    /** 搜索发送固定 f/c/q 参数。 */
    override suspend fun search(query: String, category: Category): List<Torrent> =
        load(baseBuilder().addQueryParameter("f", "0").addQueryParameter("c", categoryId(category)).addQueryParameter("q", sanitizeProviderQuery(query)).build())

    /** 详情输入在网络前精确校验。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** trustedUrl 只允许已审计来源。 */ val trustedUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** responseHtml 只在内存中传入 parser。 */ val responseHtml = networkClient.getText(trustedUrl)
        return detailsParser.parse(responseHtml, trustedUrl)
    }

    /** 最新列表只发送类别。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> =
        load(baseBuilder().addQueryParameter("c", categoryId(category)).build())

    /** 热门列表发送排序和类别。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> =
        load(baseBuilder().addQueryParameter("s", "seeders").addQueryParameter("o", "desc").addQueryParameter("c", categoryId(category)).build())

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
        /** 主站 host。 */ private const val PRIMARY_HOST = "nyaa.si"
        /** 备用 host。 */ private const val MIRROR_HOST = "nyaa.iss.ink"
        /** 主站根地址。 */ private const val BASE_URL = "https://nyaa.si/"
    }
}

/** 将请求分类映射为站点类别 ID。 */
private fun categoryId(/** 调用方类别。 */ category: Category): String = when (category) {
    Category.ANIME -> "1_0"
    Category.APPS -> "6_1"
    Category.BOOKS -> "3_0"
    Category.GAMES -> "6_2"
    Category.MUSIC -> "2_0"
    Category.SERIES -> "4_0"
    else -> "0_0"
}

/** 将站点完整类别 ID 映射为领域类别。 */
private fun categoryFromId(/** 结构化类别 ID。 */ id: String?): Category = when (id) {
    "1_0", "1_1", "1_2", "1_3", "1_4" -> Category.ANIME
    "2_0", "2_1", "2_2" -> Category.MUSIC
    "3_0", "3_1", "3_2", "3_3" -> Category.BOOKS
    "4_0", "4_1", "4_2", "4_3", "4_4" -> Category.SERIES
    "5_0", "5_1", "5_2" -> Category.OTHER
    "6_0", "6_1" -> Category.APPS
    "6_2" -> Category.GAMES
    else -> Category.OTHER
}
