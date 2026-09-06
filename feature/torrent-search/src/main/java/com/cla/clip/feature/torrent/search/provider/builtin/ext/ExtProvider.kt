package com.cla.clip.feature.torrent.search.provider.builtin.ext

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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 固定快照 Ext search/details/latest/top Provider；两阶段网络顺序留在调用协程。 */
internal class ExtProvider(
    /** 功能内共享网络客户端，也是页面、表单和描述 JSON 的唯一网络入口。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** HMAC 秒值和相对日期使用的可注入当前毫秒。 */
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    /** DOM、JSON、HMAC、日期和领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchProvider, TorrentDetailsProvider, LatestTorrentsProvider, TopTorrentsProvider {
    /** Ext 采用本地 manifest 的 NSFW 审计结论，并固定 Cloudflare 元数据。 */
    override val descriptor = ProviderDescriptor(
        id = ProviderId("extdotto"),
        displayName = "Ext",
        baseUrl = BASE_URL,
        cloudflareSolverUrl = "$BASE_URL/browse/?q=ubuntu",
        supportedCategories = EXT_CATEGORY_IDS.keys,
        safetyLevel = ProviderSafetyLevel.NSFW,
        enabledByDefault = false,
        isCloudflareProtected = true,
    )
    /** manifest 审计通过的唯一精确 HTTPS host。 */
    private val urlPolicy = BuiltinProviderUrlPolicy(setOf("ext.to"))
    /** 搜索 DOM parser 只产生受限 draft 与领域结果。 */
    private val resultsParser = ExtResultsParser(descriptor, urlPolicy, parsingDispatcher)
    /** 详情 DOM parser 只产生受限 draft 与领域详情。 */
    private val detailsParser = ExtDetailsParser(descriptor, urlPolicy, currentTimeMillis, parsingDispatcher)
    /** 表单与描述响应使用的无网络 JSON parser。 */
    private val jsonParser = ExtJsonParser(parsingDispatcher)

    /** 构造 cat 在 q 之前的结构化搜索 URL，并执行顺序 magnet 补全。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** 搜索 URL 由 HttpUrl 编码用户查询和固定分类 ID。 */
        val requestUrl = ExtUrlBuilder.buildSearchUrl(query, category)
        return loadResults(requestUrl)
    }

    /** latest 固定按 age 降序和四日窗口，非 ALL 分类附在末尾。 */
    override suspend fun getLatestTorrents(category: Category): List<Torrent> =
        loadResults(ExtUrlBuilder.buildBrowseUrl(sort = "age", category = category))

    /** top 固定按 seeds 降序和四日窗口，非 ALL 分类附在末尾。 */
    override suspend fun getTopTorrents(category: Category): List<Torrent> =
        loadResults(ExtUrlBuilder.buildBrowseUrl(sort = "seeds", category = category))

    /** 详情地址先校验 host，再按页面、magnet、可选描述顺序联网。 */
    override suspend fun getDetails(detailsPageUrl: String): TorrentDetails? {
        /** 网络前完成 HTTPS 与精确 host 校验。 */
        val trustedDetailsUrl = urlPolicy.requireHttpsUrl(detailsPageUrl).toString()
        /** 原始详情 HTML 只在内存交给 parser。 */
        val responseHtml = networkClient.getText(trustedDetailsUrl)
        /** DOM draft 在计算调度器生成，缺必填字段返回 null。 */
        val draft = detailsParser.parse(responseHtml, trustedDetailsUrl) ?: return null
        /** HMAC 秒值与表单保持同一固定时钟快照。 */
        val epochSeconds = currentTimeMillis() / MILLIS_PER_SECOND
        /** 固定详情 HMAC 在注入计算调度器生成。 */
        val hmac = withContext(parsingDispatcher) {
            ExtHmacSha256.compute(draft.torrentId, epochSeconds, draft.pageToken)
        }
        /** 详情 magnet 固定字段只在调用协程交给共享表单接口。 */
        val magnetResponse = networkClient.submitForm(
            "$BASE_URL/ajax/getTorrentMagnet.php",
            mapOf(
                "torrent_id" to draft.torrentId,
                "download_type" to "magnet",
                "timestamp" to epochSeconds.toString(),
                "hmac" to hmac,
                "sessid" to draft.sessionId,
            ),
        )
        /** 无效单条 magnet JSON 使详情不可用，不抛带正文异常。 */
        val magnetUri = jsonParser.parseMagnet(magnetResponse) ?: return null
        /** 描述按类别选择 DOM 或固定 AJAX JSON；description 可为空。 */
        val description = when (draft.category) {
            Category.MOVIES -> draft.moviePlot
            Category.SERIES -> draft.seriesPlot
            else -> loadDescription(draft)
        }
        return detailsParser.toDetails(draft, magnetUri, description)
    }

    /** 解析搜索 draft 后按 DOM 顺序执行 magnet 表单，取消后停止后续条目。 */
    private suspend fun loadResults(requestUrl: String): List<Torrent> {
        /** 固定内部 URL 也通过同一 host 白名单，防止常量漂移。 */
        val trustedRequestUrl = urlPolicy.requireHttpsUrl(requestUrl).toString()
        /** 搜索 HTML 网络读取保持调用协程。 */
        val responseHtml = networkClient.getText(trustedRequestUrl)
        /** 解析出的 envelope 包含受限 token 和无网络 draft。 */
        val envelope = resultsParser.parse(responseHtml, trustedRequestUrl)
        /** token 缺失或超限时合法页面降级为空，不发送放大表单。 */
        val sessionId = envelope.sessionId ?: return emptyList()
        /** 页面 token 与 session 同为表单必需值。 */
        val pageToken = envelope.pageToken ?: return emptyList()
        /** 保持 DOM 顺序的结果集合。 */
        val results = mutableListOf<Torrent>()
        /** draft 是当前待补 magnet 的结构化条目。 */
        for (draft in envelope.drafts) {
            currentCoroutineContext().ensureActive()
            /** 当前条目使用一次固定时钟快照，HMAC 与 timestamp 不漂移。 */
            val epochSeconds = currentTimeMillis() / MILLIS_PER_SECOND
            /** HMAC 纯计算进入注入 dispatcher。 */
            val hmac = withContext(parsingDispatcher) {
                ExtHmacSha256.compute(draft.torrentId, epochSeconds, pageToken)
            }
            /** 上源固定搜索表单字段由共享 FormBody 安全编码。 */
            val magnetResponse = networkClient.submitForm(
                "$BASE_URL/ajax/getSearchMagnet.php",
                mapOf(
                    "torrent_id" to draft.torrentId,
                    "hash" to "",
                    "name" to "",
                    "timestamp" to epochSeconds.toString(),
                    "hmac" to hmac,
                    "sessid" to sessionId,
                ),
            )
            /** 非对象、缺 url 或非法 btih 只跳过当前条目。 */
            val magnetUri = jsonParser.parseMagnet(magnetResponse) ?: continue
            results += resultsParser.toTorrent(draft, magnetUri)
        }
        return results
    }

    /** 非影视详情按固定 header 获取不可信 HTML 字符串描述。 */
    private suspend fun loadDescription(draft: ExtDetailsDraft): String? {
        /** 缺失或超限 slug 时不调用描述端点。 */
        val torrentSlug = draft.torrentSlug ?: return null
        /** id/code 由 HttpUrl 编码，禁止拼接页面字段。 */
        val requestUrl = "$BASE_URL/ajax/torrentDescription.php".toHttpUrl().newBuilder()
            .addQueryParameter("id", draft.torrentId)
            .addQueryParameter("code", torrentSlug)
            .build()
            .toString()
        /** 固定 AJAX header 通过共享 getJson headers 能力传入。 */
        val responseJson = networkClient.getJson(
            requestUrl,
            mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        return jsonParser.parseDescription(responseJson)
    }

    /** Ext 固定站点与时间常量。 */
    private companion object {
        /** manifest 审计通过的唯一根地址。 */
        private const val BASE_URL = "https://ext.to"
        /** HMAC 协议使用毫秒转秒的固定除数。 */
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** Ext 请求 URL builder；只编码审计通过的固定参数和清洗查询。 */
private object ExtUrlBuilder {
    /** 搜索 URL 的受支持分类先写 cat，ALL 只写 q。 */
    fun buildSearchUrl(query: String, category: Category): String {
        /** browse 根地址保留结尾斜杠以匹配站点契约。 */
        val builder = BASE_URL.toHttpUrl().newBuilder()
        /** 分类 ID 仅来自固定映射，未知或 ALL 不发送 cat。 */
        val categoryId = EXT_CATEGORY_IDS[category]
        if (categoryId != null) builder.addQueryParameter("cat", categoryId.toString())
        builder.addQueryParameter("q", sanitizeProviderQuery(query))
        return builder.build().toString()
    }

    /** latest/top 共用固定 sort/order/age 参数顺序，非 ALL 分类在末尾附加。 */
    fun buildBrowseUrl(sort: String, category: Category): String {
        /** 固定排序 URL 构造器，不接收任意站点地址。 */
        val builder = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", "desc")
            .addQueryParameter("age", "4")
        /** ALL 和不支持分类都不发送 cat。 */
        val categoryId = EXT_CATEGORY_IDS[category]
        if (category != Category.ALL && categoryId != null) {
            builder.addQueryParameter("cat", categoryId.toString())
        }
        return builder.build().toString()
    }

    /** Ext browse 固定根地址。 */
    private const val BASE_URL = "https://ext.to/browse/"
}

/** Ext 支持分类及固定站点 cat ID；ALL 不放入映射。 */
internal val EXT_CATEGORY_IDS = linkedMapOf(
    Category.ANIME to 7,
    Category.APPS to 5,
    Category.BOOKS to 6,
    Category.GAMES to 4,
    Category.MOVIES to 1,
    Category.MUSIC to 3,
    Category.OTHER to 8,
    Category.PORN to 10,
    Category.SERIES to 2,
)