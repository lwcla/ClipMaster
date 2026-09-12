package com.cla.clip.feature.torrent.search.torznab

import com.cla.clip.feature.torrent.search.model.contract.Category
import com.cla.clip.feature.torrent.search.model.contract.Torrent
import com.cla.clip.feature.torrent.search.network.TorrentSearchNetworkClient
import com.cla.clip.feature.torrent.search.provider.ProviderDescriptor
import com.cla.clip.feature.torrent.search.provider.ProviderId
import com.cla.clip.feature.torrent.search.provider.ProviderType
import com.cla.clip.feature.torrent.search.provider.SearchProvider
import com.cla.clip.feature.torrent.search.provider.builtin.common.sanitizeProviderQuery
import com.cla.clip.feature.torrent.search.torznab.security.TorznabApiKeyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject

/** 用户配置的 Torznab 搜索 Provider；密钥只在单次请求构造期间读取。 */
internal class TorznabSearchProvider(
    /** 已通过正式校验且不含 API Key 明文的配置。 */
    private val config: TorznabConfig,
    /** 所有 HTTP/HTTPS 请求复用功能共享可取消客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** API Key 只通过加密仓库按稳定配置身份读取。 */
    private val apiKeyStore: TorznabApiKeyStore,
    /** XML DOM 与领域映射使用的计算调度器。 */
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Android Keystore 与密文介质读取使用的 IO 调度器。 */
    private val secretDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SearchProvider {
    /** 动态描述符不默认启用，安全模式按用户配置等级过滤。 */
    override val descriptor = config.toProviderDescriptor()

    /** 构造 Torznab search 请求、读取响应并在计算调度器解析。 */
    override suspend fun search(query: String, category: Category): List<Torrent> {
        /** apiKey 在单次调用内从安全仓库读取，不保存到 Provider 字段。 */
        val apiKey = withContext(secretDispatcher) { apiKeyStore.read(config.id) }
        /** effectiveCategory 在页面选择 ALL 时使用该动态来源的配置分类。 */
        val effectiveCategory = if (category == Category.ALL) config.category else category
        /** requestBuilder 由已校验 feed URL 构造，查询和密钥交给 OkHttp 编码。 */
        val requestBuilder = config.feedUrl.toHttpUrl().newBuilder()
            .addQueryParameter("t", "search")
            .addQueryParameter("q", sanitizeProviderQuery(query))
        /** categoryCode 只在 Torznab 存在通用分类映射时添加。 */
        val categoryCode = effectiveCategory.toTorznabCategoryCode()
        if (categoryCode != null) requestBuilder.addQueryParameter("cat", categoryCode)
        if (!apiKey.isNullOrEmpty()) requestBuilder.addQueryParameter("apikey", apiKey)
        /** requestUrl 是本次调用唯一网络地址，禁止进入日志或持久层。 */
        val requestUrl = requestBuilder.build().toString()
        /** xml 响应只在内存传给纯解析器。 */
        val xml = networkClient.getText(requestUrl)
        return withContext(parsingDispatcher) { TorznabResponseParser.parse(xml, config) }
    }
}

/** 将非敏感 Torznab 配置映射为页面与搜索策略共享的动态描述符。 */
internal fun TorznabConfig.toProviderDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId(id.value),
    displayName = name,
    baseUrl = feedUrl,
    supportedCategories = if (category == Category.ALL) Category.entries.toSet() else setOf(category),
    safetyLevel = safetyLevel,
    enabledByDefault = false,
    type = ProviderType.TORZNAB,
)

/** 将统一分类映射为 Newznab/Torznab 常用顶级类别；ALL 不附限制。 */
private fun Category.toTorznabCategoryCode(): String? = when (this) {
    Category.ALL -> null
    Category.MOVIES -> "2000"
    Category.MUSIC -> "3000"
    Category.APPS -> "4000"
    Category.GAMES -> "4050"
    Category.SERIES -> "5000"
    Category.ANIME -> "5070"
    Category.PORN -> "6000"
    Category.BOOKS -> "7000"
    Category.OTHER -> "8000"
}

/** 从配置快照构造动态 Provider，供页面搜索编排按设置刷新。 */
internal class TorznabProviderFactory @Inject constructor(
    /** 功能共享网络客户端。 */
    private val networkClient: TorrentSearchNetworkClient,
    /** 功能共享 API Key 安全仓库。 */
    private val apiKeyStore: TorznabApiKeyStore,
) {
    /** productionParsingDispatcher 生产默认使用计算线程，测试可通过次构造器收敛到虚拟时间。 */
    private var productionParsingDispatcher: CoroutineDispatcher = Dispatchers.Default
    /** productionSecretDispatcher 生产默认使用 IO 线程，测试可通过次构造器收敛到虚拟时间。 */
    private var productionSecretDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** 测试专用构造器；确保动态 Provider 的全部异步边界都归测试调度器所有。 */
    internal constructor(
        /** 测试网络客户端。 */
        networkClient: TorrentSearchNetworkClient,
        /** 测试密钥仓库。 */
        apiKeyStore: TorznabApiKeyStore,
        /** 测试解析调度器。 */
        parsingDispatcher: CoroutineDispatcher,
        /** 测试密钥读取调度器。 */
        secretDispatcher: CoroutineDispatcher,
    ) : this(networkClient, apiKeyStore) {
        productionParsingDispatcher = parsingDispatcher
        productionSecretDispatcher = secretDispatcher
    }

    /** 为当前配置列表构造无网络副作用的 Provider 快照。 */
    fun create(configs: List<TorznabConfig>): List<SearchProvider> =
        configs.map { /** config 是待实例化为运行时 Provider 的非敏感配置。 */ config ->
            TorznabSearchProvider(
                config = config,
                networkClient = networkClient,
                apiKeyStore = apiKeyStore,
                parsingDispatcher = productionParsingDispatcher,
                secretDispatcher = productionSecretDispatcher,
            )
        }
}
