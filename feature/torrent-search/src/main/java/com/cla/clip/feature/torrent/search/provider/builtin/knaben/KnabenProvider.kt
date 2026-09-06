package com.cla.clip.feature.torrent.search.provider.builtin.knaben

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.LatestTorrentsProvider
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TopTorrentsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 固定快照 Knaben JSON API Provider；入口只负责请求编排。 */
internal class KnabenProvider(
    /** 功能内共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON 解析和领域映射使用的计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** Knaben 的本地 NSFW 审计、完整九类能力和默认关闭策略。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("knaben"), displayName = "Knaben", baseUrl = "https://knaben.org",
        supportedCategories = Category.entries.toSet() - Category.ALL,
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false,
    )
    /** Knaben 可选详情地址的精确来源边界。 */
    private val urlPolicy = KnabenUrlPolicy()
    /** JSON parser 不持有网络能力。 */
    private val parser = KnabenJsonParser(descriptor, urlPolicy, parsingDispatcher)

    /** 搜索提交清洗 query，并按做种数降序。 */
    override suspend fun search(query: String, category: Category): List<Torrent> = request(KnabenRequestBuilder.build(query, category, "seeders"))

    /** 最新入口省略 query，并按日期降序。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> = request(KnabenRequestBuilder.build(null, category, "date"))

    /** 热门入口省略 query，并按做种数降序。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> = request(KnabenRequestBuilder.build(null, category, "seeders"))

    /** 在调用协程完成 POST，再切到 parser 调度器处理响应。 */
    private suspend fun request(
        /** 已结构化生成且不得写日志的 JSON 正文。 */
        requestBody: String,
    ): List<Torrent> {
        /** 固定 API 地址也经批次精确策略验证，避免常量漂移。 */
        val requestUrl = urlPolicy.requireApiUrl()
        /** 原始响应只在内存传递给 parser。 */
        val response = networkClient.postJson(requestUrl, requestBody)
        return parser.parseResults(response)
    }
}
