package com.cla.clip.feature.torrent.search.provider.builtin.torrentscsv

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** TorrentsCSV 请求编排；网络留在调用协程，JSON 解析进入可注入计算调度器。 */
internal class TorrentsCsvProvider(
    /** 功能内唯一网络边界，不创建站点私有客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON tree 与领域映射的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider {
    /** 固定来源元数据，只声明上源实际提供的搜索能力。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("torrentscsv"),
        displayName = "TorrentsCSV",
        baseUrl = TorrentsCsvUrlBuilder.BASE_URL,
        supportedCategories = setOf(Category.OTHER),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = true,
    )

    /** 无网络 parser 负责严格顶层协议与逐条容错。 */
    private val jsonParser = TorrentsCsvJsonParser(
        descriptor = descriptor,
        parsingDispatcher = parsingDispatcher,
    )

    /** 构造固定搜索请求并在响应后切换到 parser 调度器。 */
    override suspend fun search(
        /** 原始搜索词只在 URL builder 内清洗与编码。 */
        query: String,
        /** TorrentsCSV 不支持服务端分类，参数只满足统一接口。 */
        category: Category,
    ): List<Torrent> {
        /** requestUrl 只由固定 host/path 和结构化 query 组成。 */
        val requestUrl = TorrentsCsvUrlBuilder.searchUrl(query)
        /** responseJson 仅在内存传给 parser，不写日志或异常。 */
        val responseJson = networkClient.getJson(requestUrl)
        return jsonParser.parseResults(responseJson)
    }
}
