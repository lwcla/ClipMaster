package com.cla.clip.feature.torrent.search.provider.builtin.internetarchive

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl

/** InternetArchive 请求编排；分类安全等级以本地 manifest 为准，网络留调用协程。 */
internal class InternetArchiveProvider(
    /** 唯一共享网络边界。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** JSON tree 与领域映射的可注入计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider {
    /** 本地审计优先于上游 Safe，默认关闭且安全模式不执行。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("internetarchive"), displayName = "InternetArchive", baseUrl = "https://archive.org",
        supportedCategories = setOf(Category.APPS, Category.BOOKS, Category.MOVIES, Category.OTHER),
        safetyLevel = ProviderSafetyLevel.NSFW, enabledByDefault = false,
    )
    /** parser 不持有网络；显式 JSON key 不依赖反射或 Kotlin 属性名。 */
    private val parser = InternetArchiveJsonParser(descriptor, parsingDispatcher)

    /** 分类与标题表达式整体编码，固定分页与字段不允许查询注入覆盖。 */
    override suspend fun search(
        /** 原始查询只参与清洗与结构化编码，不记录内容。 */
        query: String,
        /** 调用方选择的分类，映射策略遵循固定上源。 */
        category: Category,
    ): List<Torrent> {
        /** ALL 不增加 mediatype，其他分类遵循固定上源映射。 */
        val filter = if (category == Category.ALL) "" else " AND mediatype:(${InternetArchiveCategoryMapper.requestType(category)})"
        /** 全部参数交 HttpUrl 处理百分号与特殊字符。 */
        val url = descriptor.baseUrl.toHttpUrl().newBuilder().addPathSegment("advancedsearch.php")
            .addQueryParameter("q", "title:${sanitizeProviderQuery(query)}$filter")
            .addQueryParameter("fl[]", "title,item_size,publicdate,mediatype,identifier,btih")
            .addQueryParameter("rows", "100").addQueryParameter("page", "1").addQueryParameter("output", "json").build().toString()
        /** 原文只传给 parser，不进入日志。 */
        val json = networkClient.getJson(url)
        return parser.parseResults(json)
    }

    /** 详情 URL 必须通过专用路由校验，响应身份由 parser 二次确认。 */
    override suspend fun getDetails(
        /** 外部详情地址，网络前必须通过本站 URL 策略。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** 唯一资源标识限制为单个有界路径段。 */
        val identifier = InternetArchiveUrlBuilder.requireDetailsIdentifier(detailsPageUrl)
        /** 固定 metadata API 请求无用户自带 query 或 fragment。 */
        val json = networkClient.getJson(InternetArchiveUrlBuilder.metadataUrl(identifier))
        return parser.parseDetails(json, identifier)
    }
}
