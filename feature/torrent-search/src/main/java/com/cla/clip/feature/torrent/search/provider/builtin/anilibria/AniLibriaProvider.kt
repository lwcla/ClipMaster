package com.cla.clip.feature.torrent.search.provider.builtin.anilibria

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 基于固定上源 AniLibria API 的网络编排 Provider；JSON/URL 规则由独立协作者承载，
 * release torrent 并发任务仍由注入的共享网络客户端统一调度。
 */
internal class AniLibriaProvider(
    /** 功能内唯一网络边界，Provider 不创建独立 HTTP 客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON DOM 与领域映射使用的计算调度器，默认不占用调用线程。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider {
    /** 稳定 ID、域名、能力与本地安全审计元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("anilibria"),
        displayName = "AniLibria",
        baseUrl = AniLibriaUrlBuilder.DISPLAY_BASE_URL,
        supportedCategories = setOf(Category.ANIME),
        safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** AniLibria manifest 只允许 API 根域与展示页 www 域名。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("anilibria.top", "www.anilibria.top"))
    /** 独立 JSON parser 在计算调度器完成顶层解析、逐条校验和领域映射。 */
    private val jsonParser = AniLibriaJsonParser(descriptor, parsingDispatcher)

    /** 查询 release ID 并并发取回每个 release 的 torrent；网络调用不切入解析调度器。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 搜索 URL 使用独立 builder 编码并先移除控制字符。 */
        val searchUrl = AniLibriaUrlBuilder.buildSearchUrl(query)
        urlPolicy.requireHttpsUrl(searchUrl)
        /** 搜索端点返回的原始 JSON，仅在网络调用完成后交给 parser。 */
        val responseJson = networkClient.getJson(searchUrl)
        /** 最多 15 个合法 release ID 由 parser 在计算调度器提取。 */
        val releaseIds = jsonParser.parseReleaseIds(responseJson)
        return coroutineScope {
            /** 每个 release 拥有独立子任务，父搜索取消时全部向下取消。 */
            val releaseTasks = releaseIds.map { releaseId -> async { fetchReleaseTorrents(releaseId) } }
            releaseTasks.awaitAll().flatten()
        }
    }

    /** 先在调用协程读取单个 release，再把 JSON 解析与领域映射切到计算调度器。 */
    private suspend fun fetchReleaseTorrents(releaseId: Long): List<Torrent> {
        /** release ID 由数字 JSON 产生，通过独立 path segment 保持路径边界。 */
        val torrentsUrl = AniLibriaUrlBuilder.buildReleaseTorrentsUrl(releaseId)
        urlPolicy.requireHttpsUrl(torrentsUrl)
        /** 网络请求继承 Provider 调用上下文，不进入 `Dispatchers.Default` 解析块。 */
        val responseJson = networkClient.getJson(torrentsUrl)
        return jsonParser.parseReleaseTorrents(responseJson)
    }
}
