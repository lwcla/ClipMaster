package com.cla.clip.feature.torrent.search.provider.builtin.btsow

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.HttpDeniedException
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.MagnetInfoHashParser
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 固定快照 Btsow 请求编排；JSON 解析下沉，不持有页面或持久状态。 */
internal class BtsowProvider(
    /** 唯一网络入口，调用保持在调用方协程。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 响应解析与领域映射使用的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider {
    /** 固定来源元数据，默认不自动启用。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("btsow"), displayName = "Btsow", baseUrl = "https://btsow.live",
        supportedCategories = setOf(Category.OTHER), safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** 详情仅接受已审计来源。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("btsow.live"))
    /** 无网络能力的响应解析协作者。 */
    private val parser = BtsowJsonParser(descriptor, parsingDispatcher)

    /** 提交固定第一页协议，控制字符清洗后由 JSON 库转义查询。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 协议数组依次为查询、每页数量与第一页，禁止手工拼接用户输入。 */
        val payload = JsonArray(listOf(
            JsonObject(mapOf("search" to JsonPrimitive(sanitizeProviderQuery(query)))),
            JsonPrimitive(30), JsonPrimitive(1),
        )).toString()
        /** 原始正文仅传解析器，不进入日志。 */
        val response = networkClient.postJson("https://btsow.live/bts/data/api/search", payload)
        return parser.parseResults(response)
    }

    /** 校验精确详情路径并提交规范 hash，响应身份不一致返回 null。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 已校验来源的结构化地址，不允许查询、片段、用户信息和额外路径。 */
        val url = urlPolicy.requireHttpsUrl(detailsPageUrl)
        if (url.pathSegments.size != 3 || url.pathSegments.take(2) != listOf("magnet", "detail") ||
            url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()
        ) throw HttpDeniedException()
        /** hash 非法时必须在网络调用之前拒绝。 */
        val infoHash = MagnetInfoHashParser.normalize(url.pathSegments.last()) ?: throw HttpDeniedException()
        /** 详情协议只提交一个 hash。 */
        val payload = JsonArray(listOf(JsonPrimitive(infoHash))).toString()
        /** 网络不切换到计算线程。 */
        val response = networkClient.postJson("https://btsow.live/bts/data/api/magnet", payload)
        return parser.parseDetails(response, infoHash)
    }
}
