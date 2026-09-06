package com.cla.clip.feature.torrent.search.provider.builtin.filemood

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.ProviderSafetyLevel
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.model.contract.TorrentDetails
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.TorrentDetailsProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.BuiltinProviderUrlPolicy
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl

/** FileMood 请求编排；网络保留调用协程，响应交给无网络依赖的解析协作者。 */
internal class FileMoodProvider(
    /** 所有请求使用共享可取消客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** DOM 与领域映射的独立计算调度器。 */
    parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TorrentDetailsProvider {
    /** 固定上源与本地 manifest 的能力元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("filemood"), displayName = "FileMood", baseUrl = "https://filemood.com",
        supportedCategories = setOf(Category.OTHER), safetyLevel = ProviderSafetyLevel.SAFE,
        enabledByDefault = false,
    )
    /** 所有执行和产出地址只接受精确同站 HTTPS。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("filemood.com"))
    /** 解析器只持有元数据、URL 策略和计算调度器。 */
    private val parser = FileMoodHtmlParser(descriptor, urlPolicy, parsingDispatcher)

    /** 查询清洗后交 HttpUrl 编码，分类沿用上游单分类行为。 */
    override suspend fun search(
        /** 原始查询只参与清洗与结构化编码，不记录内容。 */
        query: String,
        /** 调用方选择的分类，映射策略遵循固定上源。 */
        category: Category,
    ): List<Torrent> {
        /** 地址构建防止查询改变路径与参数结构。 */
        val url = descriptor.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("result").addQueryParameter("q", sanitizeProviderQuery(query) + " in:title").build().toString()
        /** Eztv 样式 Cookie 是固定公开列表选项；其他站不添加请求头。 */
        val html = networkClient.getText(url)
        return parser.parseResults(html, url)
    }

    /** 外部详情输入在网络创建前校验，缺必填响应由 parser 返回 null。 */
    override suspend fun getDetails(
        /** 外部详情地址，网络前必须通过本站 URL 策略。 */
        detailsPageUrl: String,
    ): TorrentDetails? {
        /** 已校验的同站 URL 也作为相对链接基址。 */
        val url = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 原始响应不进入日志或异常。 */
        val html = networkClient.getText(url)
        return parser.parseDetails(html, url)
    }
}
